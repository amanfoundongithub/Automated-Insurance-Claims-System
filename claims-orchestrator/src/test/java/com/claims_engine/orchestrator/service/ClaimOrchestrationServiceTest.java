package com.claims_engine.orchestrator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.claims_engine.orchestrator.domain.ClaimAuditLogDocument;
import com.claims_engine.orchestrator.domain.ClaimAuditLogRepository;
import com.claims_engine.orchestrator.domain.ClaimLifecycleDocument;
import com.claims_engine.orchestrator.domain.ClaimLifecycleRepository;
import com.claims_engine.orchestrator.domain.ClaimLifecycleStatus;
import com.claims_engine.orchestrator.domain.Money;
import com.claims_engine.orchestrator.domain.ProcessedEventDocument;
import com.claims_engine.orchestrator.domain.ProcessedEventRepository;
import com.claims_engine.orchestrator.messaging.ClaimSubmittedPayload;
import com.claims_engine.orchestrator.messaging.EventEnvelope;
import com.claims_engine.orchestrator.messaging.PolicyValidatedPayload;
import com.claims_engine.orchestrator.messaging.SettlementCompletedPayload;
import com.claims_engine.orchestrator.messaging.SettlementFailedPayload;
import com.claims_engine.orchestrator.messaging.SettlementRequestedPublisher;
import com.claims_engine.orchestrator.messaging.ValuationCompletedPayload;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ClaimOrchestrationServiceTest {

    private ClaimLifecycleRepository lifecycleRepository;
    private ClaimAuditLogRepository auditRepository;
    private ProcessedEventRepository processedRepository;
    private SettlementRequestedPublisher publisher;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> redisValues;
    private ClaimOrchestrationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lifecycleRepository = org.mockito.Mockito.mock(ClaimLifecycleRepository.class);
        auditRepository = org.mockito.Mockito.mock(ClaimAuditLogRepository.class);
        processedRepository = org.mockito.Mockito.mock(ProcessedEventRepository.class);
        publisher = org.mockito.Mockito.mock(SettlementRequestedPublisher.class);
        redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        redisValues = org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(redisValues);
        when(redisTemplate.hasKey(any())).thenReturn(false);
        service = new ClaimOrchestrationService(
                lifecycleRepository, auditRepository, processedRepository, publisher, redisTemplate, 72);
    }

    @Test
    void registersNewClaimAsSubmitted() {
        ClaimSubmittedPayload payload = new ClaimSubmittedPayload(
                "claim-1", "pol-1", "holder-1", "AUTO", Instant.now(), Instant.now(),
                new Money(new BigDecimal("100.00"), "USD"), Map.of("city", "A"), List.of());
        EventEnvelope<ClaimSubmittedPayload> envelope = envelope("ClaimSubmitted", payload);
        when(lifecycleRepository.findById("claim-1")).thenReturn(Optional.empty());

        service.handleClaimSubmitted(envelope);

        var captor = org.mockito.ArgumentCaptor.forClass(ClaimLifecycleDocument.class);
        verify(lifecycleRepository).save(captor.capture());
        assertEquals(ClaimLifecycleStatus.SUBMITTED, captor.getValue().getStatus());
        assertEquals("pol-1", captor.getValue().getPolicyId());
        verify(auditRepository).save(any(ClaimAuditLogDocument.class));
        verify(processedRepository).save(any(ProcessedEventDocument.class));
    }

    @Test
    void ignoresDuplicateEventAndRefreshesDedupKey() {
        UUID eventId = UUID.randomUUID();
        ClaimSubmittedPayload payload = new ClaimSubmittedPayload(
                "claim-1", "pol-1", "holder-1", "AUTO", null, null, null, Map.of(), List.of());
        when(redisTemplate.hasKey("orch:dedup:event:" + eventId)).thenReturn(true);

        service.handleClaimSubmitted(envelope(eventId, "ClaimSubmitted", payload));

        verify(lifecycleRepository, never()).findById(any());
        verify(processedRepository, never()).save(any());
        verify(redisValues).set(
                org.mockito.ArgumentMatchers.eq("orch:dedup:event:" + eventId),
                org.mockito.ArgumentMatchers.eq("1"),
                any(Duration.class));
    }

    @Test
    void recordsPolicyTermsWithoutRegressingLaterLifecycleStatus() {
        ClaimLifecycleDocument lifecycle = lifecycle("claim-1", ClaimLifecycleStatus.SETTLEMENT_PENDING);
        when(lifecycleRepository.findById("claim-1")).thenReturn(Optional.of(lifecycle));
        PolicyValidatedPayload payload = new PolicyValidatedPayload(
                "claim-1", "pol-1", "holder-1",
                new Money(new BigDecimal("500.00"), "USD"),
                new Money(new BigDecimal("50.00"), "USD"),
                new BigDecimal("0.10"), Instant.now());

        service.handlePolicyValidated(envelope("PolicyValidated", payload));

        assertEquals(ClaimLifecycleStatus.SETTLEMENT_PENDING, lifecycle.getStatus());
        assertEquals(new BigDecimal("500.00"), lifecycle.getMaxCoverageAmount().amount());
        verify(auditRepository).save(any(ClaimAuditLogDocument.class));
    }

    @Test
    void automaticallyRequestsSettlementForPositiveValuation() {
        ClaimLifecycleDocument lifecycle = lifecycle("claim-1", ClaimLifecycleStatus.VALUATION_PENDING);
        when(lifecycleRepository.findById("claim-1")).thenReturn(Optional.of(lifecycle));
        ValuationCompletedPayload payload = valuation(new BigDecimal("250.00"), false);

        service.handleValuationCompleted(envelope("ValuationCompleted", payload));

        assertEquals(ClaimLifecycleStatus.SETTLEMENT_PENDING, lifecycle.getStatus());
        assertEquals(new BigDecimal("250.00"), lifecycle.getEstimatedNetPayout().amount());
        org.junit.jupiter.api.Assertions.assertNotNull(lifecycle.getSettlementReference());
        verify(publisher).publish(any(), org.mockito.ArgumentMatchers.eq("corr-1"),
                org.mockito.ArgumentMatchers.anyString());
        verify(auditRepository, org.mockito.Mockito.times(2)).save(any(ClaimAuditLogDocument.class));
    }

    @Test
    void rejectsClaimWhenValuationHasNoPositivePayout() {
        ClaimLifecycleDocument lifecycle = lifecycle("claim-1", ClaimLifecycleStatus.VALUATION_PENDING);
        when(lifecycleRepository.findById("claim-1")).thenReturn(Optional.of(lifecycle));

        service.handleValuationCompleted(envelope("ValuationCompleted", valuation(BigDecimal.ZERO, false)));

        assertEquals(ClaimLifecycleStatus.REJECTED, lifecycle.getStatus());
        assertEquals("ClaimRejected", lifecycle.getLastEventType());
        verify(publisher, never()).publish(any(), any(), any());
    }

    @Test
    void marksSettlementCompletedClaimApproved() {
        ClaimLifecycleDocument lifecycle = lifecycle("claim-1", ClaimLifecycleStatus.SETTLEMENT_PENDING);
        when(lifecycleRepository.findById("claim-1")).thenReturn(Optional.of(lifecycle));
        SettlementCompletedPayload payload = new SettlementCompletedPayload(
                "claim-1", "pol-1", "holder-1", "stl-1", "pay-1", "provider-1",
                new BigDecimal("250.00"), "USD", Instant.now());

        service.handleSettlementCompleted(envelope("SettlementCompleted", payload));

        assertEquals(ClaimLifecycleStatus.APPROVED, lifecycle.getStatus());
        assertEquals("pay-1", lifecycle.getPayoutId());
        assertEquals("provider-1", lifecycle.getProviderReference());
        verify(processedRepository).save(any(ProcessedEventDocument.class));
    }

    @Test
    void movesFailedSettlementToManualReview() {
        ClaimLifecycleDocument lifecycle = lifecycle("claim-1", ClaimLifecycleStatus.SETTLEMENT_PENDING);
        when(lifecycleRepository.findById("claim-1")).thenReturn(Optional.of(lifecycle));
        SettlementFailedPayload payload = new SettlementFailedPayload(
                "claim-1", "pol-1", "holder-1", "stl-1", "pay-1",
                "gateway unavailable", true, Instant.now());

        service.handleSettlementFailed(envelope("SettlementFailed", payload));

        assertEquals(ClaimLifecycleStatus.PENDING_MANUAL_REVIEW, lifecycle.getStatus());
        assertEquals(Boolean.TRUE, lifecycle.getRequiresManualReview());
        assertEquals("pay-1", lifecycle.getPayoutId());
    }

    @Test
    void approvesClaimAndPublishesSettlementRequest() {
        ClaimLifecycleDocument lifecycle = lifecycle("claim-1", ClaimLifecycleStatus.PENDING_MANUAL_REVIEW);
        lifecycle.setEstimatedNetPayout(new Money(new BigDecimal("75.00"), "USD"));
        when(lifecycleRepository.findById("claim-1")).thenReturn(Optional.of(lifecycle));

        ClaimLifecycleDocument approved = service.approveClaim("claim-1", "reviewed");

        assertEquals(ClaimLifecycleStatus.SETTLEMENT_PENDING, approved.getStatus());
        assertEquals("reviewed", approved.getDecisionReason());
        org.junit.jupiter.api.Assertions.assertNotNull(approved.getSettlementReference());
        verify(publisher).publish(any(), org.mockito.ArgumentMatchers.eq("corr-1"),
                org.mockito.ArgumentMatchers.anyString());
        verify(auditRepository).save(any(ClaimAuditLogDocument.class));
    }

    @Test
    void rejectsClaimWithDefaultReasonAndCannotFinalizeTwice() {
        ClaimLifecycleDocument lifecycle = lifecycle("claim-1", ClaimLifecycleStatus.PENDING_MANUAL_REVIEW);
        when(lifecycleRepository.findById("claim-1")).thenReturn(Optional.of(lifecycle));

        ClaimLifecycleDocument rejected = service.rejectClaim("claim-1", " ");

        assertEquals(ClaimLifecycleStatus.REJECTED, rejected.getStatus());
        assertEquals("Rejected after manual review", rejected.getDecisionReason());
        assertThrows(IllegalStateException.class, () -> service.rejectClaim("claim-1", "again"));
    }

    @Test
    void reportsMissingClaimWhenApproving() {
        when(lifecycleRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.approveClaim("missing", null));
    }

    private static ClaimLifecycleDocument lifecycle(String claimId, ClaimLifecycleStatus status) {
        ClaimLifecycleDocument lifecycle = new ClaimLifecycleDocument();
        lifecycle.setClaimId(claimId);
        lifecycle.setPolicyId("pol-1");
        lifecycle.setPolicyholderId("holder-1");
        lifecycle.setStatus(status);
        lifecycle.setCorrelationId("corr-1");
        return lifecycle;
    }

    private static ValuationCompletedPayload valuation(BigDecimal payout, boolean manualReview) {
        Money netPayout = payout == null ? null : new Money(payout, "USD");
        return new ValuationCompletedPayload(
                "claim-1", "pol-1", "holder-1", "LOW", manualReview, "USD",
                new Money(new BigDecimal("300.00"), "USD"),
                new Money(new BigDecimal("50.00"), "USD"),
                new Money(BigDecimal.ZERO, "USD"),
                netPayout,
                new Money(new BigDecimal("25.00"), "USD"),
                Instant.now());
    }

    private static <T> EventEnvelope<T> envelope(String eventType, T payload) {
        return envelope(UUID.randomUUID(), eventType, payload);
    }

    private static <T> EventEnvelope<T> envelope(UUID eventId, String eventType, T payload) {
        return new EventEnvelope<>(eventId, eventType, "1.0", Instant.now(),
                "test-producer", "corr-1", "cause-1", "claim-1", payload);
    }
}
