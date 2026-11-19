import json
import logging
import uuid
from datetime import datetime, timezone

import aio_pika

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
from app.valuation import calculate_valuation

logger = logging.getLogger(__name__)


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
    policy_doc = await policies_col.find_one({"_id": policy_id})

    result = calculate_valuation(claim_doc, policy_doc, payload.riskTier)
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
        "Valued claimId=%s: netPayout=%s %s reserve=%s %s outboxEventId=%s",
        claim_id,
        result.estimatedNetPayout,
        result.currency,
        result.recommendedReserve,
        result.currency,
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