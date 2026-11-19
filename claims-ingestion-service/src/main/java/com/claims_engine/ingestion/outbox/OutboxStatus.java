package com.claims_engine.ingestion.outbox;

public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}