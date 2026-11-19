package com.claims_engine.settlement.messaging;

import java.math.BigDecimal;
import java.time.Instant;

public record SettlementCompletedPayload(
        String claimId,
        String policyId,
        String policyholderId,
        String settlementReference,
        String payoutId,
        String providerReference,
        BigDecimal settledAmount,
        String currency,
        Instant settledAt
) {}