from motor.motor_asyncio import AsyncIOMotorClient
import redis.asyncio as aioredis
from app.config import settings

mongo_client = AsyncIOMotorClient(settings.mongodb_uri, uuidRepresentation="standard")
db = mongo_client.get_default_database()

risk_scores_col = db["claim_risk_scores"]
processed_events_col = db["risk_processed_events"]
outbox_events_col = db["risk_outbox_events"]

redis_client = aioredis.Redis(
    host=settings.redis_host,
    port=settings.redis_port,
    socket_timeout=settings.redis_timeout,
    decode_responses=True,
)