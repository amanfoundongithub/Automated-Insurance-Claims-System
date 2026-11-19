package com.claims_engine.settlement.gateway;

import java.math.BigDecimal;
import java.time.Instant;

public sealed interface GatewayOutcome permits GatewayOutcome.Settled, GatewayOutcome.Failed {

    record Settled(
            String providerReference,
            BigDecimal settledAmount,
            String currency,
            Instant settledAt
    ) implements GatewayOutcome {}

    record Failed(
            String reason,
            boolean retryable,
            Instant failedAt
    ) implements GatewayOutcome {}
}