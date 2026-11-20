import json
import logging
import uuid
from datetime import datetime, timezone
from decimal import Decimal
from typing import Any

import aio_pika
from bson.decimal128 import Decimal128

from app.config import settings
from app.db import (
    claims_col,
    outbox_events_col,
    policies_col,
    processed_events_col,
    redis_client,
    valuations_col,
)
from app.models import EventEnvelope, RiskScoredPayload
from app.valuation import calculate_valuation_details

logger = logging.getLogger(__name__)

ZERO = Decimal("0")


def _to_decimal(val: Any) -> Decimal | None:
    if val is None:
        return None
    if isinstance(val, Decimal128):
        return val.to_decimal()
    if isinstance(val, Decimal):
        return val
    try:
        return Decimal(str(val))
    except Exception:
        return None


async def _load_policy_doc(
    policy_id: str | None,
    claim_doc: dict[str, Any] | None,
) -> dict[str, Any] | None:
    doc: dict[str, Any] | None = None
    if policy_id:
        try:
            found = await policies_col.find_one({"_id": policy_id})
            if isinstance(found, dict):
                doc = found
            else:
                found_by_field = await policies_col.find_one({"policyId": policy_id})
                if isinstance(found_by_field, dict):
                    doc = found_by_field
        except Exception as exc:
            logger.warning("Policy lookup skipped for policyId=%s: %s", policy_id, exc)

    merged: dict[str, Any] = dict(doc) if isinstance(doc, dict) else {}
    if isinstance(claim_doc, dict):
        for key in ("maxCoverageAmount", "deductibleAmount", "coPayRatio", "subLimitAmount", "ulaeRate"):
            if key not in merged and key in claim_doc and claim_doc[key] is not None:
                merged[key] = claim_doc[key]
    return merged or None


async def _enrich_claim_with_credibility_context(
    claim_id: str,
    policy_id: str | None,
    policyholder_id: str | None,
    claim_doc: dict[str, Any] | None,
    policy_doc: dict[str, Any] | None,
) -> dict[str, Any]:
    """
    Builds an enriched claim dict containing credibility exposure count (n),
    credibility constant (K), and observed reserve factor when historical data exists,
    without mutating or failing if history is empty.
    """
    enriched: dict[str, Any] = dict(claim_doc) if isinstance(claim_doc, dict) else {}

    existing_exposure = _to_decimal(
        enriched.get("priorExposureCount")
        or enriched.get("priorClaimCount")
        or enriched.get("exposureCount")
    )
    if existing_exposure is None and isinstance(policy_doc, dict):
        existing_exposure = _to_decimal(
            policy_doc.get("priorExposureCount")
            or policy_doc.get("priorClaimCount")
            or policy_doc.get("exposureCount")
        )

    if existing_exposure is None:
        try:
            history_filter: dict[str, Any] = {"_id": {"$ne": claim_id}}
            if policyholder_id:
                history_filter["policyholderId"] = policyholder_id
            elif policy_id:
                history_filter["policyId"] = policy_id

            if policyholder_id or policy_id:
                count = 0
                observed_ratios: list[Decimal] = []
                async for hist_doc in claims_col.find(history_filter).limit(100):
                    if not isinstance(hist_doc, dict):
                        continue
                    count += 1
                    hist_id = hist_doc.get("_id") or hist_doc.get("claimId")
                    if hist_id:
                        hist_val = await valuations_col.find_one({"_id": hist_id})
                        if isinstance(hist_val, dict):
                            net = _to_decimal(hist_val.get("estimatedNetPayout"))
                            res = _to_decimal(hist_val.get("recommendedReserve"))
                            if net is not None and net > ZERO and res is not None and res > ZERO:
                                observed_ratios.append(res / net)

                enriched["priorExposureCount"] = Decimal(count)
                if observed_ratios and "observedReserveFactor" not in enriched:
                    avg_ratio = sum(observed_ratios, ZERO) / Decimal(len(observed_ratios))
                    enriched["observedReserveFactor"] = avg_ratio
        except Exception as exc:
            logger.warning(
                "Credibility history lookup failed for claimId=%s: %s", claim_id, exc
            )

    if "credibilityK" not in enriched:
        configured_k = _to_decimal(getattr(settings, "credibility_k", None))
        if configured_k is not None and configured_k > ZERO:
            enriched["credibilityK"] = configured_k

    return enriched


async def process_risk_scored(raw_body: bytes) -> None:
    data = json.loads(raw_body.decode("utf-8"))
    envelope = EventEnvelope[RiskScoredPayload](**data)

    event_id = envelope.eventId
    payload = envelope.payload
    claim_id = payload.claimId
    policy_id = payload.policyId
    redis_key = f"val:dedup:event:{event_id}"

    if await redis_client.exists(redis_key) or await processed_events_col.find_one({"_id": event_id}):
        logger.info("Duplicate RiskScored ignored in valuation-reserve: eventId=%s claimId=%s", event_id, claim_id)
        await redis_client.set(redis_key, "1", ex=settings.dedup_ttl_hours * 3600)
        return

    claim_doc = await claims_col.find_one({"_id": claim_id})
    policy_doc = await _load_policy_doc(policy_id, claim_doc if isinstance(claim_doc, dict) else None)
    enriched_claim_doc = await _enrich_claim_with_credibility_context(
        claim_id=claim_id,
        policy_id=policy_id,
        policyholder_id=payload.policyholderId,
        claim_doc=claim_doc if isinstance(claim_doc, dict) else None,
        policy_doc=policy_doc,
    )

    result, breakdown = calculate_valuation_details(
        enriched_claim_doc,
        policy_doc,
        payload.riskTier,
    )
    now = datetime.now(timezone.utc)
    outbox_event_id = str(uuid.uuid4())

    valuation_doc = {
        "_id": claim_id,
        "claimId": claim_id,
        "policyId": policy_id,
        "policyholderId": payload.policyholderId,
        "riskScore": payload.riskScore,
        "riskTier": payload.riskTier,
        "requiresManualReview": payload.requiresManualReview,
        "currency": result.currency,
        "claimedAmount": str(result.claimedAmount),
        "maxCoverageAmount": str(result.maxCoverageAmount),
        "eligibleGrossAmount": str(result.eligibleGrossAmount),
        "deductibleAmount": str(result.deductibleAmount),
        "coPayRatio": str(result.coPayRatio),
        "coPayDeductionAmount": str(result.coPayDeductionAmount),
        "estimatedNetPayout": str(result.estimatedNetPayout),
        "recommendedReserve": str(result.recommendedReserve),
        "credibilityZ": str(breakdown.credibility_z),
        "reserveBreakdown": {
            "priorReserveFactor": str(breakdown.prior_reserve_factor),
            "observedReserveFactor": str(breakdown.observed_reserve_factor),
            "effectiveReserveFactor": str(breakdown.effective_reserve_factor),
            "baselineReserve": str(breakdown.baseline_reserve),
            "credibilityReserve": str(breakdown.credibility_reserve),
        },
        "correlationId": envelope.correlationId,
        "valuedAt": now,
    }

    outbox_doc = {
        "_id": outbox_event_id,
        "eventId": outbox_event_id,
        "aggregateType": "Claim",
        "aggregateId": claim_id,
        "eventType": "ValuationCompleted",
        "eventVersion": "v1",
        "routingKey": settings.valuation_completed_routing_key,
        "correlationId": envelope.correlationId,
        "causationId": event_id,
        "payload": {
            "claimId": claim_id,
            "policyId": policy_id,
            "policyholderId": payload.policyholderId,
            "riskTier": payload.riskTier,
            "requiresManualReview": payload.requiresManualReview,
            "currency": result.currency,
            "eligibleGrossAmount": {"amount": str(result.eligibleGrossAmount), "currency": result.currency},
            "deductibleAmount": {"amount": str(result.deductibleAmount), "currency": result.currency},
            "coPayDeductionAmount": {"amount": str(result.coPayDeductionAmount), "currency": result.currency},
            "estimatedNetPayout": {"amount": str(result.estimatedNetPayout), "currency": result.currency},
            "recommendedReserve": {"amount": str(result.recommendedReserve), "currency": result.currency},
            "valuedAt": now.isoformat(),
        },
        "status": "PENDING",
        "retryCount": 0,
        "lastError": None,
        "createdAt": now,
        "publishedAt": None,
    }

    processed_doc = {
        "_id": event_id,
        "claimId": claim_id,
        "processedAt": now,
    }

    await valuations_col.replace_one({"_id": claim_id}, valuation_doc, upsert=True)
    await processed_events_col.update_one({"_id": event_id}, {"$setOnInsert": processed_doc}, upsert=True)
    await outbox_events_col.insert_one(outbox_doc)
    await redis_client.set(redis_key, "1", ex=settings.dedup_ttl_hours * 3600)

    logger.info(
        "Valued claimId=%s: netPayout=%s %s reserve=%s %s credibilityZ=%s outboxEventId=%s",
        claim_id,
        result.estimatedNetPayout,
        result.currency,
        result.recommendedReserve,
        result.currency,
        breakdown.credibility_z,
        outbox_event_id,
    )


async def start_consumer() -> aio_pika.abc.AbstractRobustConnection:
    connection = await aio_pika.connect_robust(
        host=settings.rabbitmq_host,
        port=settings.rabbitmq_port,
        login=settings.rabbitmq_username,
        password=settings.rabbitmq_password,
    )
    channel = await connection.channel()
    await channel.set_qos(prefetch_count=10)

    exchange = await channel.declare_exchange(
        settings.broker_exchange, aio_pika.ExchangeType.TOPIC, durable=True
    )
    dlx = await channel.declare_exchange(
        settings.broker_dlq_exchange, aio_pika.ExchangeType.TOPIC, durable=True
    )
    dlq = await channel.declare_queue("q.valuation-reserve.dlq", durable=True)
    await dlq.bind(dlx, routing_key="#")

    queue = await channel.declare_queue(
        settings.risk_scored_queue,
        durable=True,
        arguments={
            "x-dead-letter-exchange": settings.broker_dlq_exchange,
            "x-dead-letter-routing-key": "valuation-reserve.dlq",
        },
    )
    await queue.bind(exchange, routing_key=settings.risk_scored_routing_key)

    async def on_message(message: aio_pika.abc.AbstractIncomingMessage) -> None:
        try:
            await process_risk_scored(message.body)
            await message.ack()
        except Exception as exc:
            logger.exception("Failed processing RiskScored message: %s", exc)
            await message.nack(requeue=False)

    await queue.consume(on_message)
    return connection