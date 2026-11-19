package com.claims_engine.settlement.messaging;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record EventEnvelope<T>(
        @NotNull UUID eventId,
        @NotBlank String eventType,
        @NotBlank String eventVersion,
        @NotNull Instant occurredAt,
        @NotBlank String producer,
        @NotBlank String correlationId,
        String causationId,
        String partitionKey,
        @NotNull @Valid T payload
) {}