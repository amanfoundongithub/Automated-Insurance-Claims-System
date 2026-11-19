from datetime import datetime, timezone
import json
import logging
import uuid
import aio_pika
from app.config import settings
from app.db import outbox_events_col, processed_events_col, redis_client, risk_scores_col
from app.models import EventEnvelope, PolicyValidatedPayload
from app.scoring import compute_risk_score

logger = logging.getLogger(__name__)


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

    score, tier, flags, manual_review = compute_risk_score(payload)
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
        "Risk scored for claimId=%s: score=%s tier=%s manualReview=%s outboxEventId=%s",
        claim_id,
        score,
        tier,
        manual_review,
        outbox_event_id,
    )


async def start_consumer() -> None:
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