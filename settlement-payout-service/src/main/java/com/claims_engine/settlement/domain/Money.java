package com.claims_engine.settlement.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record Money(
        @NotNull @DecimalMin(value = "0.00") BigDecimal amount,
        @NotBlank String currency
) {}