package com.claims_engine.policy.messaging;

import com.claims_engine.policy.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;

public record PolicyValidatedPayload(
        String claimId,
        String policyId,
        String policyholderId,
        Money maxCoverageAmount,
        Money deductibleAmount,
        BigDecimal coPayRatio,
        Instant validatedAt
) {}