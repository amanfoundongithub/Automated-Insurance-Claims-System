package com.claims_engine.policy.api;

import com.claims_engine.policy.domain.Money;
import com.claims_engine.policy.domain.PolicyDocument;
import com.claims_engine.policy.domain.PolicyStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

public record PolicyResponse(
        String policyId,
        String policyholderId,
        PolicyStatus status,
        Instant effectiveFrom,
        Instant effectiveTo,
        Set<String> eligibleClaimTypes,
        Money maxCoverageAmount,
        Money deductibleAmount,
        BigDecimal coPayRatio,
        Instant createdAt,
        Instant updatedAt
) {
    public static PolicyResponse from(PolicyDocument doc) {
        return new PolicyResponse(
                doc.getPolicyId(),
                doc.getPolicyholderId(),
                doc.getStatus(),
                doc.getEffectiveFrom(),
                doc.getEffectiveTo(),
                doc.getEligibleClaimTypes(),
                doc.getMaxCoverageAmount(),
                doc.getDeductibleAmount(),
                doc.getCoPayRatio(),
                doc.getCreatedAt(),
                doc.getUpdatedAt()
        );
    }
}