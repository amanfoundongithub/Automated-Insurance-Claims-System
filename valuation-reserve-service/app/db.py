from motor.motor_asyncio import AsyncIOMotorClient
import redis.asyncio as aioredis
from app.config import settings

mongo_client = AsyncIOMotorClient(settings.mongodb_uri, uuidRepresentation="standard")
db = mongo_client.get_default_database()

# Collections owned by valuation-reserve-service
valuations_col = db["claim_valuations"]
processed_events_col = db["valuation_processed_events"]
outbox_events_col = db["valuation_outbox_events"]

# Read collections from upstream services (for claim amount & policy deductible/co-pay/coverage)
claim_lifecycle_col = db["claim_lifecycle"]
policies_col = db["policies"]

redis_client = aioredis.Redis(
    host=settings.redis_host,
    port=settings.redis_port,
    socket_timeout=settings.redis_timeout,
    decode_responses=True,
)