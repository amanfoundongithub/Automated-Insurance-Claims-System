package com.claims_engine.orchestrator.messaging;

import com.claims_engine.orchestrator.domain.Money;
import java.time.Instant;

public record ValuationCompletedPayload(
        String claimId,
        String policyId,
        String policyholderId,
        String riskTier,
        boolean requiresManualReview,
        String currency,
        Money eligibleGrossAmount,
        Money deductibleAmount,
        Money coPayDeductionAmount,
        Money estimatedNetPayout,
        Money recommendedReserve,
        Instant valuedAt
) {}