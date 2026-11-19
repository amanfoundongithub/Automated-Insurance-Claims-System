package com.claims_engine.orchestrator.domain;

import java.math.BigDecimal;

public record Money(
        BigDecimal amount,
        String currency
) {}