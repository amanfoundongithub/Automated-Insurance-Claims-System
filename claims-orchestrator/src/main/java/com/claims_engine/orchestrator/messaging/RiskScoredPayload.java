package com.claims_engine.orchestrator.messaging;

import java.time.Instant;
import java.util.List;

public record RiskScoredPayload(
        String claimId,
        String policyId,
        String policyholderId,
        int riskScore,
        String riskTier,
        List<String> riskFlags,
        boolean requiresManualReview,
        Instant scoredAt
) {}