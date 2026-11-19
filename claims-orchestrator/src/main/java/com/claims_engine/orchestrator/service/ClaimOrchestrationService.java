package com.claims_engine.orchestrator.service;

import com.claims_engine.orchestrator.domain.ClaimAuditLogDocument;
import com.claims_engine.orchestrator.domain.ClaimAuditLogRepository;
import com.claims_engine.orchestrator.domain.ClaimLifecycleDocument;
import com.claims_engine.orchestrator.domain.ClaimLifecycleRepository;
import com.claims_engine.orchestrator.domain.ClaimLifecycleStatus;
import com.claims_engine.orchestrator.domain.ProcessedEventDocument;
import com.claims_engine.orchestrator.domain.ProcessedEventRepository;
import com.claims_engine.orchestrator.messaging.ClaimSubmittedPayload;
import com.claims_engine.orchestrator.messaging.EventEnvelope;
import com.claims_engine.orchestrator.messaging.PolicyValidatedPayload;
import com.claims_engine.orchestrator.messaging.PolicyValidationFailedPayload;
import com.claims_engine.orchestrator.messaging.RiskScoredPayload;
import com.claims_engine.orchestrator.messaging.SettlementCompletedPayload;
import com.claims_engine.orchestrator.messaging.SettlementFailedPayload;
import com.claims_engine.orchestrator.messaging.SettlementRequestedPayload;
import com.claims_engine.orchestrator.messaging.SettlementRequestedPublisher;
import com.claims_engine.orchestrator.messaging.ValuationCompletedPayload;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClaimOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(ClaimOrchestrationService.class);

    private final ClaimLifecycleRepository lifecycleRepository;
    private final ClaimAuditLogRepository auditLogRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final SettlementRequestedPublisher settlementRequestedPublisher;
    private final StringRedisTemplate redisTemplate;
    private final long dedupTtlHours;

    public ClaimOrchestrationService(
            ClaimLifecycleRepository lifecycleRepository,
            ClaimAuditLogRepository auditLogRepository,
            ProcessedEventRepository processedEventRepository,
            SettlementRequestedPublisher settlementRequestedPublisher,
            StringRedisTemplate redisTemplate,
            @Value("${claims.dedup.redis-ttl-hours:72}") long dedupTtlHours
    ) {
        this.lifecycleRepository = lifecycleRepository;
        this.auditLogRepository = auditLogRepository;
        this.processedEventRepository = processedEventRepository;
        this.settlementRequestedPublisher = settlementRequestedPublisher;
        this.redisTemplate = redisTemplate;
        this.dedupTtlHours = dedupTtlHours;
    }

    @Transactional
    public void handleClaimSubmitted(EventEnvelope<ClaimSubmittedPayload> envelope) {
        UUID eventId = envelope.eventId();
        ClaimSubmittedPayload payload = envelope.payload();
        String claimId = payload.claimId();
        String redisKey = "orch:dedup:event:" + eventId;

        if (isDuplicate(redisKey, eventId, claimId)) {
            return;
        }

        ClaimLifecycleDocument lifecycle = lifecycleRepository.findById(claimId).orElse(null);
        ClaimLifecycleStatus previousStatus = lifecycle != null ? lifecycle.getStatus() : null;
        ClaimLifecycleStatus targetStatus = ClaimLifecycleStatus.SUBMITTED;

        if (lifecycle == null) {
            lifecycle = new ClaimLifecycleDocument();
            lifecycle.setClaimId(claimId);
            lifecycle.setPolicyId(payload.policyId());
            lifecycle.setPolicyholderId(payload.policyholderId());
            lifecycle.setClaimType(payload.claimType());
            lifecycle.setIncidentOccurredAt(payload.incidentOccurredAt());
            lifecycle.setReportedAt(payload.reportedAt());
            lifecycle.setClaimedAmount(payload.claimedAmount());
            lifecycle.setIncidentDetails(payload.incidentDetails());
            lifecycle.setAttachments(payload.attachments());
            lifecycle.setCreatedAt(envelope.occurredAt() != null ? envelope.occurredAt() : Instant.now());
        }

        boolean willAdvance = shouldAdvanceStatus(previousStatus, targetStatus);
        ClaimLifecycleStatus newStatus = willAdvance ? targetStatus : previousStatus;

        if (willAdvance) {
            lifecycle.setStatus(targetStatus);
            lifecycle.setLastEventType(envelope.eventType());
        }

        lifecycle.setCorrelationId(envelope.correlationId());

        String notes = willAdvance
                ? "Claim ingested and registered in orchestrator"
                : "ClaimSubmitted received out-of-order; metadata stored without regressing status";

        recordTransition(lifecycle, previousStatus, newStatus, envelope, notes);
        markProcessed(redisKey, eventId, claimId, envelope);
    }

    @Transactional
    public void handlePolicyValidated(EventEnvelope<PolicyValidatedPayload> envelope) {
        UUID eventId = envelope.eventId();
        PolicyValidatedPayload payload = envelope.payload();
        String claimId = payload.claimId();
        String redisKey = "orch:dedup:event:" + eventId;

        if (isDuplicate(redisKey, eventId, claimId)) {
            return;
        }

        ClaimLifecycleDocument lifecycle = getOrCreateLifecycle(claimId, payload.policyId(), payload.policyholderId());
        ClaimLifecycleStatus previousStatus = lifecycle.getStatus();
        ClaimLifecycleStatus targetStatus = ClaimLifecycleStatus.POLICY_VALIDATED;

        lifecycle.setMaxCoverageAmount(payload.maxCoverageAmount());
        lifecycle.setDeductibleAmount(payload.deductibleAmount());
        lifecycle.setCoPayRatio(payload.coPayRatio());
        lifecycle.setPolicyValidationReasons(List.of());

        boolean willAdvance = shouldAdvanceStatus(previousStatus, targetStatus);
        ClaimLifecycleStatus newStatus = willAdvance ? targetStatus : previousStatus;

        if (willAdvance) {
            lifecycle.setStatus(targetStatus);
            lifecycle.setLastEventType(envelope.eventType());
        }

        lifecycle.setCorrelationId(envelope.correlationId());

        String notes = willAdvance
                ? "Policy coverage validated for policyId=" + payload.policyId()
                : "PolicyValidated received out-of-order; terms recorded without regressing status (" + previousStatus + ")";

        recordTransition(lifecycle, previousStatus, newStatus, envelope, notes);
        markProcessed(redisKey, eventId, claimId, envelope);
    }

    @Transactional
    public void handlePolicyValidationFailed(EventEnvelope<PolicyValidationFailedPayload> envelope) {
        UUID eventId = envelope.eventId();
        PolicyValidationFailedPayload payload = envelope.payload();
        String claimId = payload.claimId();
        String redisKey = "orch:dedup:event:" + eventId;

        if (isDuplicate(redisKey, eventId, claimId)) {
            return;
        }

        ClaimLifecycleDocument lifecycle = getOrCreateLifecycle(claimId, payload.policyId(), payload.policyholderId());
        ClaimLifecycleStatus previousStatus = lifecycle.getStatus();
        ClaimLifecycleStatus targetStatus = ClaimLifecycleStatus.POLICY_REJECTED;

        List<String> reasons = payload.reasons() != null ? payload.reasons() : List.of("POLICY_VALIDATION_FAILED");
        lifecycle.setPolicyValidationReasons(reasons);

        boolean willAdvance = shouldAdvanceStatus(previousStatus, targetStatus);
        ClaimLifecycleStatus newStatus = willAdvance ? targetStatus : previousStatus;

        if (willAdvance) {
            lifecycle.setStatus(targetStatus);
            lifecycle.setLastEventType(envelope.eventType());
        }

        lifecycle.setCorrelationId(envelope.correlationId());

        String notes = willAdvance
                ? "Policy validation failed: " + String.join("; ", reasons)
                : "PolicyValidationFailed received out-of-order; reasons recorded without regressing status (" + previousStatus + ")";

        recordTransition(lifecycle, previousStatus, newStatus, envelope, notes);
        markProcessed(redisKey, eventId, claimId, envelope);
    }

    @Transactional
    public void handleRiskScored(EventEnvelope<RiskScoredPayload> envelope) {
        UUID eventId = envelope.eventId();
        RiskScoredPayload payload = envelope.payload();
        String claimId = payload.claimId();
        String redisKey = "orch:dedup:event:" + eventId;

        if (isDuplicate(redisKey, eventId, claimId)) {
            return;
        }

        ClaimLifecycleDocument lifecycle = getOrCreateLifecycle(claimId, payload.policyId(), payload.policyholderId());
        ClaimLifecycleStatus previousStatus = lifecycle.getStatus();

        ClaimLifecycleStatus targetStatus = payload.requiresManualReview()
                ? ClaimLifecycleStatus.PENDING_MANUAL_REVIEW
                : ClaimLifecycleStatus.RISK_SCORED;

        List<String> flags = payload.riskFlags() != null ? payload.riskFlags() : List.of();
        lifecycle.setRiskScore(payload.riskScore());
        lifecycle.setRiskTier(payload.riskTier());
        lifecycle.setRiskFlags(flags);
        lifecycle.setRequiresManualReview(payload.requiresManualReview());

        boolean willAdvance = shouldAdvanceStatus(previousStatus, targetStatus);
        ClaimLifecycleStatus newStatus = willAdvance ? targetStatus : previousStatus;

        if (willAdvance) {
            lifecycle.setStatus(targetStatus);
            lifecycle.setLastEventType(envelope.eventType());
        }

        lifecycle.setCorrelationId(envelope.correlationId());

        String notes = String.format(
                "Risk evaluated: score=%d, tier=%s, manualReview=%s, flags=%s%s",
                payload.riskScore(),
                payload.riskTier(),
                payload.requiresManualReview(),
                flags,
                willAdvance ? "" : " (status maintained at " + previousStatus + ")"
        );

        recordTransition(lifecycle, previousStatus, newStatus, envelope, notes);
        markProcessed(redisKey, eventId, claimId, envelope);
    }

    @Transactional
    public void handleValuationCompleted(EventEnvelope<ValuationCompletedPayload> envelope) {
        UUID eventId = envelope.eventId();
        ValuationCompletedPayload payload = envelope.payload();
        String claimId = payload.claimId();
        String redisKey = "orch:dedup:event:" + eventId;

        if (isDuplicate(redisKey, eventId, claimId)) {
            return;
        }

        ClaimLifecycleDocument lifecycle = getOrCreateLifecycle(claimId, payload.policyId(), payload.policyholderId());
        ClaimLifecycleStatus previousStatus = lifecycle.getStatus();

        ClaimLifecycleStatus targetStatus = payload.requiresManualReview()
                ? ClaimLifecycleStatus.PENDING_MANUAL_REVIEW
                : ClaimLifecycleStatus.VALUATION_COMPLETED;

        lifecycle.setEligibleGrossAmount(payload.eligibleGrossAmount());
        lifecycle.setDeductibleAppliedAmount(payload.deductibleAmount());
        lifecycle.setCoPayDeductionAmount(payload.coPayDeductionAmount());
        lifecycle.setEstimatedNetPayout(payload.estimatedNetPayout());
        lifecycle.setRecommendedReserve(payload.recommendedReserve());

        if (payload.requiresManualReview()) {
            lifecycle.setRequiresManualReview(true);
        }

        boolean willAdvance = shouldAdvanceStatus(previousStatus, targetStatus);
        ClaimLifecycleStatus newStatus = willAdvance ? targetStatus : previousStatus;

        if (willAdvance) {
            lifecycle.setStatus(targetStatus);
            lifecycle.setLastEventType(envelope.eventType());
        }

        lifecycle.setCorrelationId(envelope.correlationId());

        String payoutStr = payload.estimatedNetPayout() != null
                ? payload.estimatedNetPayout().amount() + " " + payload.estimatedNetPayout().currency()
                : "N/A";
        String reserveStr = payload.recommendedReserve() != null
                ? payload.recommendedReserve().amount() + " " + payload.recommendedReserve().currency()
                : "N/A";

        String notes = String.format(
                "Valuation completed: netPayout=%s, reserve=%s, manualReview=%s%s",
                payoutStr,
                reserveStr,
                payload.requiresManualReview(),
                willAdvance ? "" : " (status maintained at " + previousStatus + ")"
        );

        recordTransition(lifecycle, previousStatus, newStatus, envelope, notes);
        markProcessed(redisKey, eventId, claimId, envelope);

        // Auto-Settlement Progression when manual review is not required
        if (!payload.requiresManualReview() && shouldAdvanceStatus(lifecycle.getStatus(), ClaimLifecycleStatus.SETTLEMENT_PENDING)) {
            boolean hasPositivePayout = payload.estimatedNetPayout() != null
                    && payload.estimatedNetPayout().amount() != null
                    && payload.estimatedNetPayout().amount().signum() > 0;

            if (hasPositivePayout) {
                String settlementRef = lifecycle.getSettlementReference() != null
                        ? lifecycle.getSettlementReference()
                        : "stl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

                ClaimLifecycleStatus prevBeforeSettlement = lifecycle.getStatus();
                lifecycle.setStatus(ClaimLifecycleStatus.SETTLEMENT_PENDING);
                lifecycle.setLastEventType("SettlementRequested");
                lifecycle.setSettlementReference(settlementRef);

                recordTransition(
                        lifecycle,
                        prevBeforeSettlement,
                        ClaimLifecycleStatus.SETTLEMENT_PENDING,
                        envelope,
                        "Auto-settlement initiated with reference=" + settlementRef
                );

                // Publish settlement request to RabbitMQ for settlement-payout-service
                SettlementRequestedPayload settlementPayload = new SettlementRequestedPayload(
                        claimId,
                        lifecycle.getPolicyId(),
                        lifecycle.getPolicyholderId(),
                        settlementRef,
                        payload.estimatedNetPayout(),
                        payload.recommendedReserve(),
                        lifecycle.getRiskTier(),
                        Instant.now()
                );

                settlementRequestedPublisher.publish(
                        settlementPayload,
                        envelope.correlationId(),
                        eventId.toString()
                );
            } else {
                ClaimLifecycleStatus prevBeforeFinal = lifecycle.getStatus();
                String finalReason = "Claim rejected after valuation: net payout is zero or non-positive";
                lifecycle.setStatus(ClaimLifecycleStatus.REJECTED);
                lifecycle.setLastEventType("ClaimRejected");
                lifecycle.setSettledAt(Instant.now());
                lifecycle.setDecisionReason(finalReason);

                recordTransition(
                        lifecycle,
                        prevBeforeFinal,
                        ClaimLifecycleStatus.REJECTED,
                        envelope,
                        finalReason
                );
            }
        }
    }

    @Transactional
    public void handleSettlementCompleted(EventEnvelope<SettlementCompletedPayload> envelope) {
        UUID eventId = envelope.eventId();
        SettlementCompletedPayload payload = envelope.payload();
        String claimId = payload.claimId();
        String redisKey = "orch:dedup:event:" + eventId;

        if (isDuplicate(redisKey, eventId, claimId)) {
            return;
        }

        ClaimLifecycleDocument lifecycle = lifecycleRepository.findById(claimId).orElse(null);
        if (lifecycle == null) {
            log.warn("Received SettlementCompleted for non-existent claimId={}", claimId);
            markProcessed(redisKey, eventId, claimId, envelope);
            return;
        }

        if (lifecycle.getStatus() == ClaimLifecycleStatus.APPROVED) {
            log.info("Claim already in APPROVED status for claimId={}", claimId);
            markProcessed(redisKey, eventId, claimId, envelope);
            return;
        }

        ClaimLifecycleStatus previousStatus = lifecycle.getStatus();
        lifecycle.setStatus(ClaimLifecycleStatus.APPROVED);
        lifecycle.setLastEventType(envelope.eventType());
        lifecycle.setPayoutId(payload.payoutId());
        lifecycle.setProviderReference(payload.providerReference());
        lifecycle.setSettledAt(payload.settledAt() != null ? payload.settledAt() : Instant.now());
        lifecycle.setDecisionReason("Settlement completed via providerReference=" + payload.providerReference());
        lifecycle.setCorrelationId(envelope.correlationId());

        String notes = String.format(
                "Settlement payout executed successfully: payoutId=%s, providerReference=%s, amount=%s %s",
                payload.payoutId(),
                payload.providerReference(),
                payload.settledAmount(),
                payload.currency()
        );

        recordTransition(lifecycle, previousStatus, ClaimLifecycleStatus.APPROVED, envelope, notes);
        markProcessed(redisKey, eventId, claimId, envelope);
    }

    @Transactional
    public void handleSettlementFailed(EventEnvelope<SettlementFailedPayload> envelope) {
        UUID eventId = envelope.eventId();
        SettlementFailedPayload payload = envelope.payload();
        String claimId = payload.claimId();
        String redisKey = "orch:dedup:event:" + eventId;

        if (isDuplicate(redisKey, eventId, claimId)) {
            return;
        }

        ClaimLifecycleDocument lifecycle = lifecycleRepository.findById(claimId).orElse(null);
        if (lifecycle == null) {
            log.warn("Received SettlementFailed for non-existent claimId={}", claimId);
            markProcessed(redisKey, eventId, claimId, envelope);
            return;
        }

        if (lifecycle.getStatus() == ClaimLifecycleStatus.APPROVED || lifecycle.getStatus() == ClaimLifecycleStatus.REJECTED) {
            log.info("Terminal state reached already, ignoring SettlementFailed for claimId={}", claimId);
            markProcessed(redisKey, eventId, claimId, envelope);
            return;
        }

        ClaimLifecycleStatus previousStatus = lifecycle.getStatus();
        ClaimLifecycleStatus targetStatus = ClaimLifecycleStatus.PENDING_MANUAL_REVIEW;

        lifecycle.setStatus(targetStatus);
        lifecycle.setLastEventType(envelope.eventType());
        lifecycle.setRequiresManualReview(true);
        lifecycle.setPayoutId(payload.payoutId());
        String reviewReason = String.format(
                "Settlement payout failed (payoutId=%s, retryable=%s): %s",
                payload.payoutId(),
                payload.retryable(),
                payload.failureReason()
        );
        lifecycle.setDecisionReason(reviewReason);
        lifecycle.setCorrelationId(envelope.correlationId());

        recordTransition(lifecycle, previousStatus, targetStatus, envelope, reviewReason);
        markProcessed(redisKey, eventId, claimId, envelope);
    }

    @Transactional
    public ClaimLifecycleDocument approveClaim(String claimId, String reason) {
        ClaimLifecycleDocument lifecycle = lifecycleRepository.findById(claimId)
                .orElseThrow(() -> new IllegalArgumentException("Claim not found: " + claimId));

        if (lifecycle.getStatus() == ClaimLifecycleStatus.APPROVED || lifecycle.getStatus() == ClaimLifecycleStatus.REJECTED) {
            throw new IllegalStateException("Claim already finalized with status=" + lifecycle.getStatus());
        }

        Instant now = Instant.now();
        String settlementRef = lifecycle.getSettlementReference() != null
                ? lifecycle.getSettlementReference()
                : "stl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        ClaimLifecycleStatus previousStatus = lifecycle.getStatus();
        String resolvedReason = (reason != null && !reason.isBlank())
                ? reason
                : "Approved after manual review; settlementRef=" + settlementRef;

        lifecycle.setSettlementReference(settlementRef);
        lifecycle.setDecisionReason(resolvedReason);
        lifecycle.setStatus(ClaimLifecycleStatus.SETTLEMENT_PENDING);
        lifecycle.setLastEventType("ManualClaimApproved");

        UUID auditEventId = UUID.randomUUID();
        ClaimAuditLogDocument auditEntry = new ClaimAuditLogDocument();
        auditEntry.setAuditId("aud_" + UUID.randomUUID().toString().replace("-", ""));
        auditEntry.setClaimId(claimId);
        auditEntry.setEventId(auditEventId);
        auditEntry.setEventType("ManualClaimApproved");
        auditEntry.setPreviousStatus(previousStatus);
        auditEntry.setNewStatus(ClaimLifecycleStatus.SETTLEMENT_PENDING);
        auditEntry.setCorrelationId(lifecycle.getCorrelationId());
        auditEntry.setCausationId(null);
        auditEntry.setNotes(resolvedReason);
        auditEntry.setOccurredAt(now);
        auditEntry.setCreatedAt(now);

        lifecycleRepository.save(lifecycle);
        auditLogRepository.save(auditEntry);

        // Publish to settlement-payout-service
        if (lifecycle.getEstimatedNetPayout() != null && lifecycle.getEstimatedNetPayout().amount() != null) {
            SettlementRequestedPayload settlementPayload = new SettlementRequestedPayload(
                    claimId,
                    lifecycle.getPolicyId(),
                    lifecycle.getPolicyholderId(),
                    settlementRef,
                    lifecycle.getEstimatedNetPayout(),
                    lifecycle.getRecommendedReserve(),
                    lifecycle.getRiskTier(),
                    now
            );
            settlementRequestedPublisher.publish(
                    settlementPayload,
                    lifecycle.getCorrelationId(),
                    auditEventId.toString()
            );
        }

        return lifecycle;
    }

    @Transactional
    public ClaimLifecycleDocument rejectClaim(String claimId, String reason) {
        ClaimLifecycleDocument lifecycle = lifecycleRepository.findById(claimId)
                .orElseThrow(() -> new IllegalArgumentException("Claim not found: " + claimId));

        if (lifecycle.getStatus() == ClaimLifecycleStatus.APPROVED || lifecycle.getStatus() == ClaimLifecycleStatus.REJECTED) {
            throw new IllegalStateException("Claim already finalized with status=" + lifecycle.getStatus());
        }

        Instant now = Instant.now();
        ClaimLifecycleStatus previousStatus = lifecycle.getStatus();
        String resolvedReason = (reason != null && !reason.isBlank())
                ? reason
                : "Rejected after manual review";

        lifecycle.setSettledAt(now);
        lifecycle.setDecisionReason(resolvedReason);
        lifecycle.setStatus(ClaimLifecycleStatus.REJECTED);
        lifecycle.setLastEventType("ManualClaimRejected");

        ClaimAuditLogDocument auditEntry = new ClaimAuditLogDocument();
        auditEntry.setAuditId("aud_" + UUID.randomUUID().toString().replace("-", ""));
        auditEntry.setClaimId(claimId);
        auditEntry.setEventId(UUID.randomUUID());
        auditEntry.setEventType("ManualClaimRejected");
        auditEntry.setPreviousStatus(previousStatus);
        auditEntry.setNewStatus(ClaimLifecycleStatus.REJECTED);
        auditEntry.setCorrelationId(lifecycle.getCorrelationId());
        auditEntry.setCausationId(null);
        auditEntry.setNotes(resolvedReason);
        auditEntry.setOccurredAt(now);
        auditEntry.setCreatedAt(now);

        lifecycleRepository.save(lifecycle);
        auditLogRepository.save(auditEntry);
        return lifecycle;
    }

    private boolean shouldAdvanceStatus(ClaimLifecycleStatus current, ClaimLifecycleStatus target) {
        if (current == null) {
            return true;
        }
        return statusRank(target) > statusRank(current);
    }

    private int statusRank(ClaimLifecycleStatus status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case SUBMITTED -> 1;
            case POLICY_VALIDATION_PENDING -> 2;
            case POLICY_VALIDATED -> 3;
            case POLICY_REJECTED -> 3;
            case RISK_SCORING_PENDING -> 4;
            case RISK_SCORED, PENDING_MANUAL_REVIEW -> 5;
            case VALUATION_PENDING -> 6;
            case VALUATION_COMPLETED -> 6;
            case SETTLEMENT_PENDING -> 7;
            case APPROVED, REJECTED -> 8;
        };
    }

    private boolean isDuplicate(String redisKey, UUID eventId, String claimId) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(redisKey)) || processedEventRepository.existsById(eventId)) {
            log.info("Duplicate event ignored in orchestrator: eventId={} claimId={}", eventId, claimId);
            redisTemplate.opsForValue().set(redisKey, "1", Duration.ofHours(dedupTtlHours));
            return true;
        }
        return false;
    }

    private ClaimLifecycleDocument getOrCreateLifecycle(String claimId, String policyId, String policyholderId) {
        return lifecycleRepository.findById(claimId).orElseGet(() -> {
            ClaimLifecycleDocument doc = new ClaimLifecycleDocument();
            doc.setClaimId(claimId);
            doc.setPolicyId(policyId);
            doc.setPolicyholderId(policyholderId);
            doc.setCreatedAt(Instant.now());
            return doc;
        });
    }

    private void recordTransition(
            ClaimLifecycleDocument lifecycle,
            ClaimLifecycleStatus previousStatus,
            ClaimLifecycleStatus newStatus,
            EventEnvelope<?> envelope,
            String notes
    ) {
        if (lifecycle.getCreatedAt() == null) {
            lifecycle.setCreatedAt(envelope.occurredAt() != null ? envelope.occurredAt() : Instant.now());
        }

        ClaimAuditLogDocument auditEntry = new ClaimAuditLogDocument();
        auditEntry.setAuditId("aud_" + UUID.randomUUID().toString().replace("-", ""));
        auditEntry.setClaimId(lifecycle.getClaimId());
        auditEntry.setEventId(envelope.eventId());
        auditEntry.setEventType(envelope.eventType());
        auditEntry.setPreviousStatus(previousStatus);
        auditEntry.setNewStatus(newStatus);
        auditEntry.setCorrelationId(envelope.correlationId());
        auditEntry.setCausationId(envelope.causationId());
        auditEntry.setNotes(notes);
        auditEntry.setOccurredAt(envelope.occurredAt() != null ? envelope.occurredAt() : Instant.now());
        auditEntry.setCreatedAt(Instant.now());

        lifecycleRepository.save(lifecycle);
        auditLogRepository.save(auditEntry);
    }

    private void markProcessed(String redisKey, UUID eventId, String claimId, EventEnvelope<?> envelope) {
        ProcessedEventDocument processedEvent = new ProcessedEventDocument();
        processedEvent.setEventId(eventId);
        processedEvent.setClaimId(claimId);
        processedEvent.setEventType(envelope.eventType());
        processedEvent.setCorrelationId(envelope.correlationId());
        processedEvent.setProcessedAt(Instant.now());

        try {
            processedEventRepository.save(processedEvent);
            redisTemplate.opsForValue().set(redisKey, "1", Duration.ofHours(dedupTtlHours));
            log.info("Orchestrated {}: claimId={} eventId={} correlationId={}",
                    envelope.eventType(), claimId, eventId, envelope.correlationId());
        } catch (DuplicateKeyException dup) {
            log.info("Concurrent duplicate eventId caught on insert: eventId={} claimId={}", eventId, claimId);
            redisTemplate.opsForValue().set(redisKey, "1", Duration.ofHours(dedupTtlHours));
        }
    }
}