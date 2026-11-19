import asyncio
from datetime import datetime, timezone
import json
import logging
import aio_pika
from app.config import settings
from app.db import outbox_events_col

logger = logging.getLogger(__name__)


async def run_outbox_publisher() -> None:
    connection = await aio_pika.connect_robust(
        host=settings.rabbitmq_host,
        port=settings.rabbitmq_port,
        login=settings.rabbitmq_username,
        password=settings.rabbitmq_password,
    )
    channel = await connection.channel()
    exchange = await channel.declare_exchange(
        settings.broker_exchange, aio_pika.ExchangeType.TOPIC, durable=True
    )

    while True:
        try:
            cursor = (
                outbox_events_col.find({"status": "PENDING"})
                .sort("createdAt", 1)
                .limit(settings.outbox_batch_size)
            )
            async for doc in cursor:
                now = datetime.now(timezone.utc)
                envelope = {
                    "eventId": doc["eventId"],
                    "eventType": doc["eventType"],
                    "eventVersion": doc["eventVersion"],
                    "occurredAt": now.isoformat(),
                    "producer": settings.app_name,
                    "correlationId": doc.get("correlationId"),
                    "causationId": doc.get("causationId"),
                    "partitionKey": doc["aggregateId"],
                    "payload": doc["payload"],
                }

                headers = {}
                if doc.get("correlationId"):
                    headers["X-Correlation-Id"] = doc["correlationId"]

                amqp_msg = aio_pika.Message(
                    body=json.dumps(envelope, default=str).encode("utf-8"),
                    delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                    message_id=doc["eventId"],
                    headers=headers,
                )

                try:
                    await exchange.publish(amqp_msg, routing_key=doc["routingKey"])
                    await outbox_events_col.update_one(
                        {"_id": doc["_id"]},
                        {
                            "$set": {
                                "status": "PUBLISHED",
                                "publishedAt": now,
                                "lastError": None,
                            }
                        },
                    )
                    logger.info(
                        "Published risk outbox event: eventId=%s routingKey=%s claimId=%s",
                        doc["eventId"],
                        doc["routingKey"],
                        doc["aggregateId"],
                    )
                except Exception as pub_exc:
                    next_retry = doc.get("retryCount", 0) + 1
                    new_status = "FAILED" if next_retry >= settings.outbox_max_retries else "PENDING"
                    await outbox_events_col.update_one(
                        {"_id": doc["_id"]},
                        {
                            "$set": {
                                "retryCount": next_retry,
                                "lastError": str(pub_exc),
                                "status": new_status,
                            }
                        },
                    )
                    logger.warning("Outbox publish failed (retry=%s): %s", next_retry, pub_exc)
        except asyncio.CancelledError:
            break
        except Exception as loop_exc:
            logger.exception("Error in risk outbox loop: %s", loop_exc)

        await asyncio.sleep(settings.outbox_poll_interval_seconds)

    await connection.close()