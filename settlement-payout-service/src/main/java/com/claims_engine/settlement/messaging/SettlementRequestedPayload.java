package com.claims_engine.settlement.messaging;

import com.claims_engine.settlement.domain.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record SettlementRequestedPayload(
        @NotBlank String claimId,
        @NotBlank String policyId,
        @NotBlank String policyholderId,
        @NotBlank String settlementReference,
        @NotNull @Valid Money netPayout,
        Money recommendedReserve,
        String riskTier,
        Instant requestedAt
) {}