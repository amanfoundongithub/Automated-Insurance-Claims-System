package com.claims_engine.settlement.gateway;

import com.claims_engine.settlement.domain.PayoutDocument;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SimulatedPayoutGatewayClient implements PayoutGatewayClient {

    private final PayoutGatewayProperties properties;

    @Override
    public GatewayOutcome executePayout(PayoutDocument payout) {
        BigDecimal rawAmount = payout.getAmount();
        String currency = payout.getCurrency();

        if (rawAmount == null || rawAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return new GatewayOutcome.Failed(
                    "Invalid payout amount: must be greater than zero",
                    false,
                    Instant.now()
            );
        }

        if (currency == null || currency.isBlank()) {
            return new GatewayOutcome.Failed(
                    "Invalid payout currency: currency code is required",
                    false,
                    Instant.now()
            );
        }

        BigDecimal normalizedAmount = rawAmount.setScale(2, RoundingMode.HALF_UP);
        String normalizedCurrency = currency.trim().toUpperCase();

        if (properties.isSimulatePermanentFailure()) {
            return new GatewayOutcome.Failed(
                    "Gateway rejected payout: beneficiary account validation failed",
                    false,
                    Instant.now()
            );
        }

        if (properties.getMaxSinglePayoutAmount() != null
                && normalizedAmount.compareTo(properties.getMaxSinglePayoutAmount()) > 0) {
            return new GatewayOutcome.Failed(
                    "Payout amount " + normalizedAmount + " " + normalizedCurrency
                            + " exceeds single rail limit " + properties.getMaxSinglePayoutAmount(),
                    false,
                    Instant.now()
            );
        }

        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        int transientFailuresRemaining = Math.max(0, properties.getSimulateTransientFailures());
        GatewayOutcome.Failed lastTransientFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (transientFailuresRemaining > 0) {
                    transientFailuresRemaining--;
                    throw new TransientGatewayException("Simulated payment rail timeout on attempt " + attempt);
                }

                String providerReference = "UTR-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
                return new GatewayOutcome.Settled(
                        providerReference,
                        normalizedAmount,
                        normalizedCurrency,
                        Instant.now()
                );
            } catch (TransientGatewayException ex) {
                log.warn(
                        "Transient gateway error payoutId={} claimId={} attempt={}/{} reason={}",
                        payout.getPayoutId(),
                        payout.getClaimId(),
                        attempt,
                        maxAttempts,
                        ex.getMessage()
                );
                lastTransientFailure = new GatewayOutcome.Failed(ex.getMessage(), true, Instant.now());

                if (attempt < maxAttempts) {
                    sleepBackoff(attempt);
                }
            }
        }

        return lastTransientFailure != null
                ? lastTransientFailure
                : new GatewayOutcome.Failed("Payment rail attempts exhausted", true, Instant.now());
    }

    private void sleepBackoff(int attempt) {
        long baseMillis = Math.max(0L, properties.getRetryBackoff().toMillis());
        if (baseMillis == 0L) {
            return;
        }
        long delayMillis = baseMillis * (1L << Math.min(attempt - 1, 5));
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class TransientGatewayException extends RuntimeException {
        TransientGatewayException(String message) {
            super(message);
        }
    }
}