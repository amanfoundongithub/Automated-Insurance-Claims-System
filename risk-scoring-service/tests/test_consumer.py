import json
import os
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
from bson.decimal128 import Decimal128

os.environ.setdefault("MONGODB_URI", "mongodb://localhost/test")

from app import consumer


def event_body(event_id: str = "event-1", payload: dict | None = None) -> bytes:
    return json.dumps(
        {
            "eventId": event_id,
            "eventType": "PolicyValidated",
            "correlationId": "corr-1",
            "payload": payload
            or {
                "claimId": "claim-1",
                "policyId": "policy-1",
                "policyholderId": "holder-1",
                "claimedAmount": {"amount": "200.00", "currency": "USD"},
                "maxCoverageAmount": {"amount": "1000.00", "currency": "USD"},
                "attachmentCount": 2,
            },
        }
    ).encode()


class ProcessPolicyValidatedTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.redis = SimpleNamespace(exists=AsyncMock(return_value=False), set=AsyncMock())
        self.claims = SimpleNamespace(find_one=AsyncMock(return_value=None))
        self.processed = SimpleNamespace(
            find_one=AsyncMock(return_value=None), update_one=AsyncMock()
        )
        self.scores = SimpleNamespace(replace_one=AsyncMock())
        self.outbox = SimpleNamespace(insert_one=AsyncMock())
        self.patches = [
            patch.object(consumer, "redis_client", self.redis),
            patch.object(consumer, "claims_col", self.claims),
            patch.object(consumer, "processed_events_col", self.processed),
            patch.object(consumer, "risk_scores_col", self.scores),
            patch.object(consumer, "outbox_events_col", self.outbox),
        ]
        for active_patch in self.patches:
            active_patch.start()
            self.addCleanup(active_patch.stop)

    async def test_persists_score_and_pending_outbox_event(self) -> None:
        await consumer.process_policy_validated(event_body())

        score_doc = self.scores.replace_one.await_args.args[1]
        self.assertEqual("claim-1", score_doc["claimId"])
        self.assertEqual(15, score_doc["riskScore"])
        self.assertEqual("LOW", score_doc["riskTier"])
        outbox_doc = self.outbox.insert_one.await_args.args[0]
        self.assertEqual("RiskScored", outbox_doc["eventType"])
        self.assertEqual("event-1", outbox_doc["causationId"])
        self.assertEqual("PENDING", outbox_doc["status"])
        self.redis.set.assert_awaited_once()

    async def test_falls_back_to_claim_amount_and_attachment_count(self) -> None:
        self.claims.find_one.return_value = {
            "claimedAmount": {"amount": "600.00", "currency": "USD"},
            "attachments": [{"id": "a1"}],
        }
        payload = {
            "claimId": "claim-1",
            "policyId": "policy-1",
            "policyholderId": "holder-1",
            "maxCoverageAmount": {"amount": "1000.00", "currency": "USD"},
        }

        await consumer.process_policy_validated(event_body(payload=payload))

        score_doc = self.scores.replace_one.await_args.args[1]
        self.assertEqual(30, score_doc["riskScore"])
        self.assertEqual(["MODERATE_COVERAGE_UTILIZATION"], score_doc["riskFlags"])
        self.claims.find_one.assert_awaited_once_with({"_id": "claim-1"})

    async def test_duplicate_in_redis_skips_scoring_and_refreshes_dedup_key(self) -> None:
        self.redis.exists.return_value = True

        await consumer.process_policy_validated(event_body())

        self.scores.replace_one.assert_not_awaited()
        self.outbox.insert_one.assert_not_awaited()
        self.redis.set.assert_awaited_once()

    async def test_duplicate_in_mongo_skips_scoring(self) -> None:
        self.processed.find_one.return_value = {"_id": "event-1"}

        await consumer.process_policy_validated(event_body())

        self.scores.replace_one.assert_not_awaited()
        self.outbox.insert_one.assert_not_awaited()
        self.redis.set.assert_awaited_once()

    async def test_zero_event_amount_uses_claim_record_fallback(self) -> None:
        self.claims.find_one.return_value = {
            "claimedAmount": {"amount": "900.00", "currency": "USD"},
            "attachments": [{"id": "a1"}, {"id": "a2"}],
        }
        payload = {
            "claimId": "claim-1",
            "policyId": "policy-1",
            "policyholderId": "holder-1",
            "claimedAmount": {"amount": "0", "currency": "USD"},
            "maxCoverageAmount": {"amount": "1000.00", "currency": "USD"},
        }

        await consumer.process_policy_validated(event_body(payload=payload))

        score_doc = self.scores.replace_one.await_args.args[1]
        self.assertEqual(45, score_doc["riskScore"])
        self.assertEqual(["HIGH_COVERAGE_UTILIZATION"], score_doc["riskFlags"])
        self.assertEqual("MEDIUM", score_doc["riskTier"])

    async def test_missing_claim_record_still_scores_without_supporting_attachments(self) -> None:
        payload = {
            "claimId": "claim-1",
            "policyId": "policy-1",
            "policyholderId": "holder-1",
        }

        await consumer.process_policy_validated(event_body(payload=payload))

        score_doc = self.scores.replace_one.await_args.args[1]
        self.assertEqual(35, score_doc["riskScore"])
        self.assertEqual(["NO_SUPPORTING_ATTACHMENTS"], score_doc["riskFlags"])

    async def test_explicit_attachment_count_is_not_overridden_by_claim_document(self) -> None:
        self.claims.find_one.return_value = {
            "claimedAmount": {"amount": "600.00", "currency": "USD"},
            "attachments": [{"id": "a1"}, {"id": "a2"}],
        }
        payload = {
            "claimId": "claim-1",
            "policyId": "policy-1",
            "policyholderId": "holder-1",
            "maxCoverageAmount": {"amount": "1000.00", "currency": "USD"},
            "attachmentCount": 4,
        }

        await consumer.process_policy_validated(event_body(payload=payload))

        score_doc = self.scores.replace_one.await_args.args[1]
        self.assertEqual(30, score_doc["riskScore"])
        self.assertEqual(["MODERATE_COVERAGE_UTILIZATION"], score_doc["riskFlags"])

    async def test_converts_decimal128_claim_amount_from_mongo(self) -> None:
        self.claims.find_one.return_value = {
            "claimedAmount": {
                "amount": Decimal128("900.00"),
                "currency": "USD",
            }
        }
        payload = {
            "claimId": "claim-1",
            "policyId": "policy-1",
            "policyholderId": "holder-1",
            "maxCoverageAmount": {"amount": "1000.00", "currency": "USD"},
        }

        await consumer.process_policy_validated(event_body(payload=payload))

        score_doc = self.scores.replace_one.await_args.args[1]
        self.assertEqual(65, score_doc["riskScore"])
        self.assertEqual(
            ["HIGH_COVERAGE_UTILIZATION", "NO_SUPPORTING_ATTACHMENTS"],
            score_doc["riskFlags"],
        )


if __name__ == "__main__":
    unittest.main()
