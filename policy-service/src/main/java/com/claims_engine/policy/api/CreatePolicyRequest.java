package com.claims_engine.policy.api;

import com.claims_engine.policy.domain.Money;
import com.claims_engine.policy.domain.PolicyStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

public record CreatePolicyRequest(
        @NotBlank(message = "policyId is required")
        String policyId,

        @NotBlank(message = "policyholderId is required")
        String policyholderId,

        @NotNull(message = "status is required")
        PolicyStatus status,

        @NotNull(message = "effectiveFrom is required")
        Instant effectiveFrom,

        @NotNull(message = "effectiveTo is required")
        Instant effectiveTo,

        @NotEmpty(message = "eligibleClaimTypes must not be empty")
        Set<@NotBlank String> eligibleClaimTypes,

        @NotNull(message = "maxCoverageAmount is required")
        @Valid
        Money maxCoverageAmount,

        @NotNull(message = "deductibleAmount is required")
        @Valid
        Money deductibleAmount,

        @NotNull(message = "coPayRatio is required")
        @DecimalMin(value = "0.00", inclusive = true)
        @DecimalMax(value = "1.00", inclusive = true)
        BigDecimal coPayRatio
) {}