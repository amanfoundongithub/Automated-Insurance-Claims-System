package com.claims_engine.settlement.service;

import com.claims_engine.settlement.domain.PayoutAuditLogDocument;
import com.claims_engine.settlement.domain.PayoutAuditLogRepository;
import com.claims_engine.settlement.domain.PayoutDocument;
import com.claims_engine.settlement.domain.PayoutRepository;
import com.claims_engine.settlement.domain.PayoutStatus;
import com.claims_engine.settlement.domain.ProcessedEventDocument;
import com.claims_engine.settlement.domain.ProcessedEventRepository;
import com.claims_engine.settlement.gateway.GatewayOutcome;
import com.claims_engine.settlement.gateway.PayoutGatewayClient;
import com.claims_engine.settlement.messaging.EventEnvelope;
import com.claims_engine.settlement.messaging.SettlementCompletedPayload;
import com.claims_engine.settlement.messaging.SettlementEventPublisher;
import com.claims_engine.settlement.messaging.SettlementFailedPayload;
import com.claims_engine.settlement.messaging.SettlementRequestedPayload;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementPayoutService {

    private final PayoutRepository payoutRepository;
    private final PayoutAuditLogRepository auditLogRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final PayoutGatewayClient payoutGatewayClient;
    private final SettlementEventPublisher eventPublisher;
    private final StringRedisTemplate redisTemplate;

    @Value("${settlement.dedup.ttl-hours:72}")
    private long dedupTtlHours;

    public void handleSettlementRequested(EventEnvelope<SettlementRequestedPayload> envelope) {
        UUID eventId = envelope.eventId();
        SettlementRequestedPayload payload = envelope.payload();
        String claimId = payload.claimId();
        String correlationId = envelope.correlationId();
        String causationId = envelope.causationId() != null ? envelope.causationId() : eventId.toString();

        if (isAlreadyProcessed(eventId)) {
            log.info(
                    "Duplicate event ignored eventId={} claimId={} correlationId={}",
                    eventId,
                    claimId,
                    correlationId
            );
            return;
        }

        PayoutDocument payout = payoutRepository.findByClaimId(claimId).orElse(null);

        if (payout != null && payout.getStatus() == PayoutStatus.SETTLED) {
            log.info(
                    "Claim already settled payoutId={} claimId={} settlementReference={}",
                    payout.getPayoutId(),
                    claimId,
                    payout.getSettlementReference()
            );
            markEventProcessed(eventId, claimId, envelope.eventType());
            return;
        }

        Instant now = Instant.now();
        BigDecimal normalizedAmount = payload.netPayout() != null && payload.netPayout().amount() != null
                ? payload.netPayout().amount().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        String currency = payload.netPayout() != null && payload.netPayout().currency() != null
                ? payload.netPayout().currency().trim().toUpperCase()
                : null;

        if (payout == null) {
            payout = PayoutDocument.builder()
                    .payoutId("pay_" + UUID.randomUUID())
                    .claimId(claimId)
                    .policyId(payload.policyId())
                    .policyholderId(payload.policyholderId())
                    .settlementReference(payload.settlementReference())
                    .amount(normalizedAmount)
                    .currency(currency)
                    .riskTier(payload.riskTier())
                    .status(PayoutStatus.INITIATED)
                    .attemptCount(0)
                    .lastCorrelationId(correlationId)
                    .lastCausationId(causationId)
                    .build();
            payout = payoutRepository.save(payout);
            recordAudit(
                    payout,
                    eventId,
                    envelope.eventType(),
                    null,
                    PayoutStatus.INITIATED,
                    correlationId,
                    causationId,
                    "Payout record initialized for settlementReference=" + payload.settlementReference(),
                    now
            );
        } else {
            payout.setAmount(normalizedAmount);
            payout.setCurrency(currency);
        }

        PayoutStatus previousStatus = payout.getStatus();
        payout.setStatus(PayoutStatus.PROCESSING);
        payout.setAttemptCount(payout.getAttemptCount() + 1);
        payout.setLastCorrelationId(correlationId);
        payout.setLastCausationId(causationId);
        payout = payoutRepository.save(payout);

        recordAudit(
                payout,
                eventId,
                envelope.eventType(),
                previousStatus,
                PayoutStatus.PROCESSING,
                correlationId,
                causationId,
                "Payout execution started (attempt #" + payout.getAttemptCount() + ")",
                Instant.now()
        );

        GatewayOutcome outcome = payoutGatewayClient.executePayout(payout);

        switch (outcome) {
            case GatewayOutcome.Settled settled -> {
                payout.setStatus(PayoutStatus.SETTLED);
                payout.setAmount(settled.settledAmount());
                payout.setCurrency(settled.currency());
                payout.setProviderReference(settled.providerReference());
                payout.setFailureReason(null);
                payout.setRetryable(false);
                payout.setFailedAt(null);
                payout.setSettledAt(settled.settledAt());
                payoutRepository.save(payout);

                recordAudit(
                        payout,
                        eventId,
                        "SettlementCompleted",
                        PayoutStatus.PROCESSING,
                        PayoutStatus.SETTLED,
                        correlationId,
                        eventId.toString(),
                        "Settled via providerReference=" + settled.providerReference(),
                        settled.settledAt()
                );

                markEventProcessed(eventId, claimId, envelope.eventType());

                eventPublisher.publishSettlementCompleted(
                        new SettlementCompletedPayload(
                                claimId,
                                payout.getPolicyId(),
                                payout.getPolicyholderId(),
                                payout.getSettlementReference(),
                                payout.getPayoutId(),
                                settled.providerReference(),
                                settled.settledAmount(),
                                settled.currency(),
                                settled.settledAt()
                        ),
                        correlationId,
                        eventId.toString()
                );
            }
            case GatewayOutcome.Failed failed -> {
                Instant failedAt = failed.failedAt() != null ? failed.failedAt() : Instant.now();
                payout.setStatus(PayoutStatus.FAILED);
                payout.setFailureReason(failed.reason());
                payout.setRetryable(failed.retryable());
                payout.setFailedAt(failedAt);
                payoutRepository.save(payout);

                recordAudit(
                        payout,
                        eventId,
                        "SettlementFailed",
                        PayoutStatus.PROCESSING,
                        PayoutStatus.FAILED,
                        correlationId,
                        eventId.toString(),
                        failed.reason() + " (retryable=" + failed.retryable() + ")",
                        failedAt
                );

                markEventProcessed(eventId, claimId, envelope.eventType());

                eventPublisher.publishSettlementFailed(
                        new SettlementFailedPayload(
                                claimId,
                                payout.getPolicyId(),
                                payout.getPolicyholderId(),
                                payout.getSettlementReference(),
                                payout.getPayoutId(),
                                failed.reason(),
                                failed.retryable(),
                                failedAt
                        ),
                        correlationId,
                        eventId.toString()
                );
            }
        }
    }

    private boolean isAlreadyProcessed(UUID eventId) {
        String redisKey = "settlement:dedup:event:" + eventId;
        try {
            Boolean exists = redisTemplate.hasKey(redisKey);
            if (Boolean.TRUE.equals(exists)) {
                return true;
            }
        } catch (Exception ex) {
            log.warn("Redis dedup check failed for eventId={}, falling back to MongoDB", eventId, ex);
        }
        return processedEventRepository.existsById(eventId);
    }

    private void markEventProcessed(UUID eventId, String claimId, String eventType) {
        try {
            processedEventRepository.save(
                    ProcessedEventDocument.builder()
                            .eventId(eventId)
                            .claimId(claimId)
                            .eventType(eventType)
                            .processedAt(Instant.now())
                            .build()
            );
        } catch (DuplicateKeyException ex) {
            log.info("Concurrent duplicate eventId caught on insert: eventId={} claimId={}", eventId, claimId);
        }
        String redisKey = "settlement:dedup:event:" + eventId;
        try {
            redisTemplate.opsForValue().set(redisKey, "1", Duration.ofHours(dedupTtlHours));
        } catch (Exception ex) {
            log.warn("Failed to set Redis dedup key for eventId={}", eventId, ex);
        }
    }

    private void recordAudit(
            PayoutDocument payout,
            UUID eventId,
            String eventType,
            PayoutStatus previousStatus,
            PayoutStatus newStatus,
            String correlationId,
            String causationId,
            String notes,
            Instant occurredAt
    ) {
        auditLogRepository.save(
                PayoutAuditLogDocument.builder()
                        .auditId("aud_" + UUID.randomUUID())
                        .payoutId(payout.getPayoutId())
                        .claimId(payout.getClaimId())
                        .eventId(eventId)
                        .eventType(eventType)
                        .previousStatus(previousStatus)
                        .newStatus(newStatus)
                        .correlationId(correlationId)
                        .causationId(causationId)
                        .notes(notes)
                        .occurredAt(occurredAt)
                        .build()
        );
    }
}