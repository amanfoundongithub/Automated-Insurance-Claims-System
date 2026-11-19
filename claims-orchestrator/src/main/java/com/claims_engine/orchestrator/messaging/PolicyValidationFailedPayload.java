package com.claims_engine.orchestrator.messaging;

import java.time.Instant;
import java.util.List;

public record PolicyValidationFailedPayload(
        String claimId,
        String policyId,
        String policyholderId,
        List<String> reasons,
        Instant evaluatedAt
) {}