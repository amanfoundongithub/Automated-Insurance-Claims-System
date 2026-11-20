import json
import os
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

os.environ.setdefault("MONGODB_URI", "mongodb://localhost/test")

from app import consumer


class EmptyAsyncCursor:
    def limit(self, _limit: int) -> "EmptyAsyncCursor":
        return self

    def __aiter__(self) -> "EmptyAsyncCursor":
        return self

    async def __anext__(self) -> dict:
        raise StopAsyncIteration


def event_body(event_id: str = "event-1", requires_manual_review: bool = False) -> bytes:
    return json.dumps(
        {
            "eventId": event_id,
            "eventType": "RiskScored",
            "correlationId": "corr-1",
            "payload": {
                "claimId": "claim-1",
                "policyId": "policy-1",
                "policyholderId": "holder-1",
                "riskScore": 45,
                "riskTier": "MEDIUM",
                "requiresManualReview": requires_manual_review,
            },
        }
    ).encode()


class ProcessRiskScoredTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.redis = SimpleNamespace(exists=AsyncMock(return_value=False), set=AsyncMock())
        self.claims = SimpleNamespace(
            find_one=AsyncMock(return_value={"claimedAmount": {"amount": "1000", "currency": "USD"}}),
            find=MagicMock(return_value=EmptyAsyncCursor()),
        )
        self.policies = SimpleNamespace(
            find_one=AsyncMock(
                return_value={
                    "maxCoverageAmount": {"amount": "800", "currency": "USD"},
                    "deductibleAmount": {"amount": "100", "currency": "USD"},
                    "coPayRatio": "0.25",
                }
            )
        )
        self.processed = SimpleNamespace(
            find_one=AsyncMock(return_value=None), update_one=AsyncMock()
        )
        self.valuations = SimpleNamespace(replace_one=AsyncMock())
        self.outbox = SimpleNamespace(insert_one=AsyncMock())
        self.patches = [
            patch.object(consumer, "redis_client", self.redis),
            patch.object(consumer, "claims_col", self.claims),
            patch.object(consumer, "policies_col", self.policies),
            patch.object(consumer, "processed_events_col", self.processed),
            patch.object(consumer, "valuations_col", self.valuations),
            patch.object(consumer, "outbox_events_col", self.outbox),
        ]
        for active_patch in self.patches:
            active_patch.start()
            self.addCleanup(active_patch.stop)

    async def test_persists_valuation_and_completion_outbox(self) -> None:
        await consumer.process_risk_scored(event_body())

        valuation_doc = self.valuations.replace_one.await_args.args[1]
        self.assertEqual("claim-1", valuation_doc["claimId"])
        self.assertEqual("800.00", valuation_doc["eligibleGrossAmount"])
        self.assertEqual("525.00", valuation_doc["estimatedNetPayout"])
        self.assertEqual("551.25", valuation_doc["recommendedReserve"])
        outbox_doc = self.outbox.insert_one.await_args.args[0]
        self.assertEqual("ValuationCompleted", outbox_doc["eventType"])
        self.assertEqual("event-1", outbox_doc["causationId"])
        self.assertEqual("525.00", outbox_doc["payload"]["estimatedNetPayout"]["amount"])
        self.redis.set.assert_awaited_once()

    async def test_missing_upstream_documents_produce_zero_inr_valuation(self) -> None:
        self.claims.find_one.return_value = None
        self.policies.find_one.return_value = None

        await consumer.process_risk_scored(event_body())

        valuation_doc = self.valuations.replace_one.await_args.args[1]
        self.assertEqual("INR", valuation_doc["currency"])
        self.assertEqual("0.00", valuation_doc["estimatedNetPayout"])

    async def test_duplicate_in_redis_skips_database_writes(self) -> None:
        self.redis.exists.return_value = True

        await consumer.process_risk_scored(event_body())

        self.claims.find_one.assert_not_awaited()
        self.policies.find_one.assert_not_awaited()
        self.valuations.replace_one.assert_not_awaited()
        self.outbox.insert_one.assert_not_awaited()
        self.redis.set.assert_awaited_once()

    async def test_duplicate_in_mongo_skips_valuation(self) -> None:
        self.processed.find_one.return_value = {"_id": "event-1"}

        await consumer.process_risk_scored(event_body())

        self.claims.find_one.assert_not_awaited()
        self.valuations.replace_one.assert_not_awaited()
        self.outbox.insert_one.assert_not_awaited()

    async def test_preserves_manual_review_decision_in_valuation_and_event(self) -> None:
        await consumer.process_risk_scored(event_body(requires_manual_review=True))

        valuation_doc = self.valuations.replace_one.await_args.args[1]
        outbox_doc = self.outbox.insert_one.await_args.args[0]
        self.assertTrue(valuation_doc["requiresManualReview"])
        self.assertTrue(outbox_doc["payload"]["requiresManualReview"])

    async def test_missing_policy_caps_eligible_amount_at_claimed_amount(self) -> None:
        self.claims.find_one.return_value = {
            "claimedAmount": {"amount": "175.00", "currency": "EUR"}
        }
        self.policies.find_one.return_value = None

        await consumer.process_risk_scored(event_body())

        valuation_doc = self.valuations.replace_one.await_args.args[1]
        self.assertEqual("175.00", valuation_doc["maxCoverageAmount"])
        self.assertEqual("175.00", valuation_doc["eligibleGrossAmount"])
        self.assertEqual("EUR", valuation_doc["currency"])


if __name__ == "__main__":
    unittest.main()
