package com.claims_engine.settlement.service;

import com.claims_engine.settlement.domain.PayoutAuditLogDocument;
import com.claims_engine.settlement.domain.PayoutAuditLogRepository;
import com.claims_engine.settlement.domain.PayoutDocument;
import com.claims_engine.settlement.domain.PayoutRepository;
import com.claims_engine.settlement.domain.PayoutStatus;
import com.claims_engine.settlement.domain.ProcessedEventDocument;
import com.claims_engine.settlement.domain.ProcessedEventRepository;
import com.claims_engine.settlement.messaging.EventEnvelope;
import com.claims_engine.settlement.messaging.SettlementCompletedPayload;
import com.claims_engine.settlement.messaging.SettlementEventPublisher;
import com.claims_engine.settlement.messaging.SettlementFailedPayload;
import com.claims_engine.settlement.messaging.SettlementRequestedPayload;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementPayoutService {

    private final PayoutRepository payoutRepository;
    private final PayoutAuditLogRepository auditLogRepository;
    private final ProcessedEventRepository processedEventRepository;
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

        if (payout == null) {
            payout = PayoutDocument.builder()
                    .payoutId("pay_" + UUID.randomUUID())
                    .claimId(claimId)
                    .policyId(payload.policyId())
                    .policyholderId(payload.policyholderId())
                    .settlementReference(payload.settlementReference())
                    .amount(payload.netPayout().amount())
                    .currency(payload.netPayout().currency())
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

        BigDecimal payoutAmount = payout.getAmount();
        if (payoutAmount == null || payoutAmount.compareTo(BigDecimal.ZERO) <= 0) {
            Instant failedAt = Instant.now();
            payout.setStatus(PayoutStatus.FAILED);
            payout.setFailureReason("Payout amount must be greater than zero");
            payout.setRetryable(false);
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
                    payout.getFailureReason(),
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
                            payout.getFailureReason(),
                            false,
                            failedAt
                    ),
                    correlationId,
                    eventId.toString()
            );
            return;
        }

        // Execute provider/rail transfer (simulated deterministic gateway reference; swap with external client when ready)
        String providerReference = "UTR-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        Instant settledAt = Instant.now();

        payout.setStatus(PayoutStatus.SETTLED);
        payout.setProviderReference(providerReference);
        payout.setFailureReason(null);
        payout.setRetryable(false);
        payout.setSettledAt(settledAt);
        payoutRepository.save(payout);

        recordAudit(
                payout,
                eventId,
                "SettlementCompleted",
                PayoutStatus.PROCESSING,
                PayoutStatus.SETTLED,
                correlationId,
                eventId.toString(),
                "Settled via providerReference=" + providerReference,
                settledAt
        );

        markEventProcessed(eventId, claimId, envelope.eventType());

        eventPublisher.publishSettlementCompleted(
                new SettlementCompletedPayload(
                        claimId,
                        payout.getPolicyId(),
                        payout.getPolicyholderId(),
                        payout.getSettlementReference(),
                        payout.getPayoutId(),
                        providerReference,
                        payout.getAmount(),
                        payout.getCurrency(),
                        settledAt
                ),
                correlationId,
                eventId.toString()
        );
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
        processedEventRepository.save(
                ProcessedEventDocument.builder()
                        .eventId(eventId)
                        .claimId(claimId)
                        .eventType(eventType)
                        .processedAt(Instant.now())
                        .build()
        );
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