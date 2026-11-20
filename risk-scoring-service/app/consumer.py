import json
import logging
import uuid
from datetime import datetime, timezone
from decimal import Decimal
from typing import Any

import aio_pika
from bson.decimal128 import Decimal128

from app import db as app_db
from app.config import settings
from app.db import (
    claims_col,
    outbox_events_col,
    processed_events_col,
    redis_client,
    risk_scores_col,
)
from app.models import EventEnvelope, Money, PolicyValidatedPayload
from app.scoring import evaluate_risk

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


def _extract_money(money_obj: Any) -> Money | None:
    if not isinstance(money_obj, dict):
        return None
    amt = _to_decimal(money_obj.get("amount"))
    cur = money_obj.get("currency")
    if amt is None or not cur:
        return None
    return Money(amount=amt, currency=str(cur))


async def _load_policy_doc(policy_id: str | None) -> dict[str, Any] | None:
    policies_col = getattr(app_db, "policies_col", None)
    if not policy_id or policies_col is None:
        return None
    try:
        doc = await policies_col.find_one({"_id": policy_id})
        if isinstance(doc, dict):
            return doc
        doc = await policies_col.find_one({"policyId": policy_id})
        return doc if isinstance(doc, dict) else None
    except Exception as exc:
        logger.warning("Policy lookup skipped for policyId=%s: %s", policy_id, exc)
        return None


async def _load_prior_experience(
    claim_id: str,
    policy_id: str | None,
    policyholder_id: str | None,
    claim_type: str | None,
) -> tuple[int, int, Decimal | None]:
    """
    Returns (prior_claim_count, prior_adverse_count, baseline_claim_amount)
    from historical records without failing scoring if history is empty or unavailable.
    """
    prior_claim_count = 0
    prior_adverse_count = 0
    baseline_claim_amount: Decimal | None = None

    try:
        history_filter: dict[str, Any] = {"_id": {"$ne": claim_id}}
        if policyholder_id:
            history_filter["policyholderId"] = policyholder_id
        elif policy_id:
            history_filter["policyId"] = policy_id

        prior_docs: list[dict[str, Any]] = []
        if policyholder_id or policy_id:
            cursor = claims_col.find(history_filter).limit(100)
            prior_docs = [doc async for doc in cursor if isinstance(doc, dict)]

        prior_claim_count = len(prior_docs)
        for doc in prior_docs:
            status = str(doc.get("status") or doc.get("lifecycleStatus") or "").upper()
            if status in {"REJECTED", "FRAUD_FLAGGED", "DISPUTED", "HIGH_RISK"}:
                prior_adverse_count += 1
                continue

            hist_claim_id = doc.get("_id") or doc.get("claimId")
            if hist_claim_id:
                score_doc = await risk_scores_col.find_one({"_id": hist_claim_id})
                if isinstance(score_doc, dict):
                    tier = str(score_doc.get("riskTier") or "").upper()
                    if tier in {"MEDIUM", "HIGH"}:
                        prior_adverse_count += 1

        if claim_type:
            type_cursor = claims_col.find(
                {"_id": {"$ne": claim_id}, "claimType": claim_type}
            ).limit(100)
            amounts: list[Decimal] = []
            async for doc in type_cursor:
                if isinstance(doc, dict):
                    money = _extract_money(doc.get("claimedAmount"))
                    if money is not None and money.amount > ZERO:
                        amounts.append(money.amount)
            if amounts:
                baseline_claim_amount = sum(amounts, ZERO) / Decimal(len(amounts))

    except Exception as exc:
        logger.warning(
            "Historical experience lookup failed for claimId=%s: %s", claim_id, exc
        )

    return prior_claim_count, prior_adverse_count, baseline_claim_amount


async def process_policy_validated(raw_body: bytes) -> None:
    data = json.loads(raw_body.decode("utf-8"))
    envelope = EventEnvelope[PolicyValidatedPayload](**data)

    event_id = envelope.eventId
    payload = envelope.payload
    claim_id = payload.claimId
    redis_key = f"risk:dedup:event:{event_id}"

    if await redis_client.exists(redis_key) or await processed_events_col.find_one({"_id": event_id}):
        logger.info("Duplicate PolicyValidated ignored: eventId=%s claimId=%s", event_id, claim_id)
        await redis_client.set(redis_key, "1", ex=settings.dedup_ttl_hours * 3600)
        return

    claim_doc = await claims_col.find_one({"_id": claim_id})
    policy_doc = await _load_policy_doc(payload.policyId)

    updates: dict[str, Any] = {}

    # Claimed amount fallback from claims collection if missing or <= 0
    if payload.claimedAmount is None or (
        payload.claimedAmount.amount is not None and payload.claimedAmount.amount <= ZERO
    ):
        fallback_claimed = (
            _extract_money(claim_doc.get("claimedAmount")) if isinstance(claim_doc, dict) else None
        )
        if fallback_claimed is not None:
            updates["claimedAmount"] = fallback_claimed

    # Attachment count fallback from claims collection
    claim_attachments = claim_doc.get("attachments") if isinstance(claim_doc, dict) else None
    fallback_attachment_count = len(claim_attachments) if isinstance(claim_attachments, list) else 0
    if getattr(payload, "attachmentCount", 0) == 0 and fallback_attachment_count > 0:
        updates["attachmentCount"] = fallback_attachment_count

    # Coverage cap & deductible enrichment from policy/claim documents when absent on payload
    max_cov_fallback = _extract_money(policy_doc.get("maxCoverageAmount")) if policy_doc else None
    if max_cov_fallback is None and isinstance(claim_doc, dict):
        max_cov_fallback = _extract_money(claim_doc.get("maxCoverageAmount"))
    if getattr(payload, "maxCoverageAmount", None) is None and max_cov_fallback is not None:
        updates["maxCoverageAmount"] = max_cov_fallback

    deductible_fallback = _extract_money(policy_doc.get("deductibleAmount")) if policy_doc else None
    if deductible_fallback is None and isinstance(claim_doc, dict):
        deductible_fallback = _extract_money(claim_doc.get("deductibleAmount"))
    if getattr(payload, "deductibleAmount", None) is None and deductible_fallback is not None:
        updates["deductibleAmount"] = deductible_fallback

    # Credibility & severity context factors
    claim_type = (
        getattr(payload, "claimType", None)
        or (claim_doc.get("claimType") if isinstance(claim_doc, dict) else None)
    )
    prior_claim_count, prior_adverse_count, baseline_claim_amount = await _load_prior_experience(
        claim_id=claim_id,
        policy_id=payload.policyId,
        policyholder_id=payload.policyholderId,
        claim_type=claim_type,
    )

    configured_k = _to_decimal(getattr(settings, "credibility_k", None)) or Decimal("10")
    updates["priorClaimCount"] = prior_claim_count
    updates["priorAdverseCount"] = prior_adverse_count
    updates["credibilityK"] = configured_k
    if baseline_claim_amount is not None:
        updates["baselineClaimAmount"] = baseline_claim_amount

    scoring_payload = payload.model_copy(update=updates) if updates else payload
    assessment = evaluate_risk(scoring_payload)

    score = assessment.score
    tier = assessment.tier
    flags = assessment.flags
    manual_review = assessment.requires_manual_review

    now = datetime.now(timezone.utc)
    outbox_event_id = str(uuid.uuid4())

    score_doc = {
        "_id": claim_id,
        "claimId": claim_id,
        "policyId": payload.policyId,
        "policyholderId": payload.policyholderId,
        "riskScore": score,
        "riskTier": tier,
        "riskFlags": flags,
        "requiresManualReview": manual_review,
        "credibilityZ": str(assessment.credibility_z),
        "scoreBreakdown": {
            k: str(v) if isinstance(v, Decimal) else v
            for k, v in assessment.breakdown.items()
        },
        "correlationId": envelope.correlationId,
        "scoredAt": now,
    }

    outbox_doc = {
        "_id": outbox_event_id,
        "eventId": outbox_event_id,
        "aggregateType": "Claim",
        "aggregateId": claim_id,
        "eventType": "RiskScored",
        "eventVersion": "v1",
        "routingKey": settings.risk_scored_routing_key,
        "correlationId": envelope.correlationId,
        "causationId": event_id,
        "payload": {
            "claimId": claim_id,
            "policyId": payload.policyId,
            "policyholderId": payload.policyholderId,
            "riskScore": score,
            "riskTier": tier,
            "riskFlags": flags,
            "requiresManualReview": manual_review,
            "scoredAt": now.isoformat(),
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

    await risk_scores_col.replace_one({"_id": claim_id}, score_doc, upsert=True)
    await processed_events_col.update_one({"_id": event_id}, {"$setOnInsert": processed_doc}, upsert=True)
    await outbox_events_col.insert_one(outbox_doc)
    await redis_client.set(redis_key, "1", ex=settings.dedup_ttl_hours * 3600)

    logger.info(
        "Risk scored for claimId=%s: score=%s tier=%s credibilityZ=%s manualReview=%s outboxEventId=%s",
        claim_id,
        score,
        tier,
        assessment.credibility_z,
        manual_review,
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
    dlq = await channel.declare_queue("q.risk-scoring.dlq", durable=True)
    await dlq.bind(dlx, routing_key="#")

    queue = await channel.declare_queue(
        settings.policy_validated_queue,
        durable=True,
        arguments={
            "x-dead-letter-exchange": settings.broker_dlq_exchange,
            "x-dead-letter-routing-key": "risk-scoring.dlq",
        },
    )
    await queue.bind(exchange, routing_key=settings.policy_validated_routing_key)

    async def on_message(message: aio_pika.abc.AbstractIncomingMessage) -> None:
        try:
            await process_policy_validated(message.body)
            await message.ack()
        except Exception as exc:
            logger.exception("Failed processing PolicyValidated message: %s", exc)
            await message.nack(requeue=False)

    await queue.consume(on_message)
    return connection