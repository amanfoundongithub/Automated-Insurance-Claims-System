package com.claims_engine.orchestrator.messaging;

import com.claims_engine.orchestrator.domain.Money;
import java.time.Instant;

public record SettlementRequestedPayload(
        String claimId,
        String policyId,
        String policyholderId,
        String settlementReference,
        Money netPayout,
        Money recommendedReserve,
        String riskTier,
        Instant requestedAt
) {}