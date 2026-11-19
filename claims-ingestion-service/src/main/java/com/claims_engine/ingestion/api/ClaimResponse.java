package com.claims_engine.ingestion.api;

import com.claims_engine.ingestion.domain.ClaimStatus;
import java.time.Instant;
import java.util.UUID;

public record ClaimResponse(
        String claimId,
        String policyId,
        ClaimStatus status,
        UUID eventId,
        String correlationId,
        Instant submittedAt
) {}