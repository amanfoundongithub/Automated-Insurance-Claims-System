package com.claims_engine.settlement.messaging;

import java.time.Instant;

public record SettlementFailedPayload(
        String claimId,
        String policyId,
        String policyholderId,
        String settlementReference,
        String payoutId,
        String failureReason,
        boolean retryable,
        Instant failedAt
) {}