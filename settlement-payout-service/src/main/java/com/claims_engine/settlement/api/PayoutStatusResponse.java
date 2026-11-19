package com.claims_engine.settlement.api;

import com.claims_engine.settlement.domain.PayoutDocument;
import com.claims_engine.settlement.domain.PayoutStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record PayoutStatusResponse(
        String payoutId,
        String claimId,
        String policyId,
        String policyholderId,
        String settlementReference,
        BigDecimal amount,
        String currency,
        String riskTier,
        PayoutStatus status,
        String providerReference,
        String failureReason,
        boolean retryable,
        int attemptCount,
        String lastCorrelationId,
        String lastCausationId,
        Instant settledAt,
        Instant failedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static PayoutStatusResponse from(PayoutDocument doc) {
        return new PayoutStatusResponse(
                doc.getPayoutId(),
                doc.getClaimId(),
                doc.getPolicyId(),
                doc.getPolicyholderId(),
                doc.getSettlementReference(),
                doc.getAmount(),
                doc.getCurrency(),
                doc.getRiskTier(),
                doc.getStatus(),
                doc.getProviderReference(),
                doc.getFailureReason(),
                doc.isRetryable(),
                doc.getAttemptCount(),
                doc.getLastCorrelationId(),
                doc.getLastCausationId(),
                doc.getSettledAt(),
                doc.getFailedAt(),
                doc.getCreatedAt(),
                doc.getUpdatedAt()
        );
    }
}