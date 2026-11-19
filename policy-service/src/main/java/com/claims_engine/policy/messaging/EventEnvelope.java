package com.claims_engine.policy.messaging;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        String eventVersion,
        Instant occurredAt,
        String producer,
        String correlationId,
        String causationId,
        String partitionKey,
        T payload
) {}