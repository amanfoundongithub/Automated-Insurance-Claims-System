from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "risk-scoring-service"
    server_port: int = 8084

    mongodb_uri: str

    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_timeout: float = 2.0
    dedup_ttl_hours: int = 72

    rabbitmq_host: str = "localhost"
    rabbitmq_port: int = 5672
    rabbitmq_username: str = "guest"
    rabbitmq_password: str = "guest"

    broker_exchange: str = "claims.events.topic"
    broker_dlq_exchange: str = "claims.events.dlx"
    policy_validated_queue: str = "q.risk-valuation.policy-validated"
    policy_validated_routing_key: str = "policy.validated"
    risk_scored_routing_key: str = "risk.scored"

    outbox_poll_interval_seconds: float = 1.0
    outbox_batch_size: int = 50
    outbox_max_retries: int = 5

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


settings = Settings()