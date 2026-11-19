package com.claims_engine.settlement.gateway;

import java.math.BigDecimal;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "settlement.gateway")
public class PayoutGatewayProperties {

    /**
     * Maximum rail execution attempts per payout request (including the initial attempt).
     */
    private int maxAttempts = 3;

    /**
     * Base backoff between transient rail retry attempts.
     */
    private Duration retryBackoff = Duration.ofMillis(200);

    /**
     * Optional test hook: simulate a transient failure for the first N attempts.
     */
    private int simulateTransientFailures = 0;

    /**
     * Optional test hook: simulate a non-retryable rail rejection (e.g., invalid beneficiary account).
     */
    private boolean simulatePermanentFailure = false;

    /**
     * Optional test hook: fail payouts strictly greater than this threshold as non-retryable.
     */
    private BigDecimal maxSinglePayoutAmount;
}