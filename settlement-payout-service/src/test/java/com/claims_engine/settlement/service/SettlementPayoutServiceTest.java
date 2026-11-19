package com.claims_engine.settlement.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.claims_engine.settlement.domain.Money;
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
import com.claims_engine.settlement.messaging.SettlementEventPublisher;
import com.claims_engine.settlement.messaging.SettlementRequestedPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class SettlementPayoutServiceTest {

    private PayoutRepository payoutRepository;
    private PayoutAuditLogRepository auditRepository;
    private ProcessedEventRepository processedRepository;
    private PayoutGatewayClient gatewayClient;
    private SettlementEventPublisher eventPublisher;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> redisValues;
    private SettlementPayoutService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        payoutRepository = org.mockito.Mockito.mock(PayoutRepository.class);
        auditRepository = org.mockito.Mockito.mock(PayoutAuditLogRepository.class);
        processedRepository = org.mockito.Mockito.mock(ProcessedEventRepository.class);
        gatewayClient = org.mockito.Mockito.mock(PayoutGatewayClient.class);
        eventPublisher = org.mockito.Mockito.mock(SettlementEventPublisher.class);
        redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        redisValues = org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(redisValues);
        when(redisTemplate.hasKey(any())).thenReturn(false);
        when(payoutRepository.save(any(PayoutDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new SettlementPayoutService(
                payoutRepository, auditRepository, processedRepository, gatewayClient, eventPublisher, redisTemplate);
    }

    @Test
    void settlesNewPayoutAndPublishesCompletion() {
        UUID eventId = UUID.randomUUID();
        when(payoutRepository.findByClaimId("claim-1")).thenReturn(Optional.empty());
        when(gatewayClient.executePayout(any(PayoutDocument.class))).thenReturn(
                new GatewayOutcome.Settled("provider-1", new BigDecimal("125.50"), "USD", Instant.now()));

        service.handleSettlementRequested(envelope(eventId, payload("125.496", " usd ")));

        var payoutCaptor = org.mockito.ArgumentCaptor.forClass(PayoutDocument.class);
        verify(gatewayClient).executePayout(payoutCaptor.capture());
        assertEquals(new BigDecimal("125.50"), payoutCaptor.getValue().getAmount());
        assertEquals("USD", payoutCaptor.getValue().getCurrency());
        assertEquals(1, payoutCaptor.getValue().getAttemptCount());
        assertEquals(PayoutStatus.SETTLED, payoutCaptor.getValue().getStatus());
        verify(eventPublisher).publishSettlementCompleted(any(), org.mockito.ArgumentMatchers.eq("corr-1"),
                org.mockito.ArgumentMatchers.eq(eventId.toString()));
        verify(processedRepository).save(any(ProcessedEventDocument.class));
        verify(auditRepository, org.mockito.Mockito.times(3)).save(any(PayoutAuditLogDocument.class));
    }

    @Test
    void recordsGatewayFailureAndPublishesFailure() {
        UUID eventId = UUID.randomUUID();
        Instant failedAt = Instant.parse("2025-03-01T10:00:00Z");
        when(payoutRepository.findByClaimId("claim-1")).thenReturn(Optional.empty());
        when(gatewayClient.executePayout(any(PayoutDocument.class))).thenReturn(
                new GatewayOutcome.Failed("temporary outage", true, failedAt));

        service.handleSettlementRequested(envelope(eventId, payload("80.00", "USD")));

        var payoutCaptor = org.mockito.ArgumentCaptor.forClass(PayoutDocument.class);
        verify(payoutRepository, org.mockito.Mockito.times(3)).save(payoutCaptor.capture());
        PayoutDocument finalState = payoutCaptor.getAllValues().get(2);
        assertEquals(PayoutStatus.FAILED, finalState.getStatus());
        assertEquals("temporary outage", finalState.getFailureReason());
        org.junit.jupiter.api.Assertions.assertTrue(finalState.isRetryable());
        assertEquals(failedAt, finalState.getFailedAt());
        verify(eventPublisher).publishSettlementFailed(any(), org.mockito.ArgumentMatchers.eq("corr-1"),
                org.mockito.ArgumentMatchers.eq(eventId.toString()));
    }

    @Test
    void usesCurrentTimeWhenGatewayFailureHasNoTimestamp() {
        when(payoutRepository.findByClaimId("claim-1")).thenReturn(Optional.empty());
        when(gatewayClient.executePayout(any(PayoutDocument.class))).thenReturn(
                new GatewayOutcome.Failed("declined", false, null));
        Instant before = Instant.now();

        service.handleSettlementRequested(envelope(UUID.randomUUID(), payload("20.00", "USD")));

        var payoutCaptor = org.mockito.ArgumentCaptor.forClass(PayoutDocument.class);
        verify(payoutRepository, org.mockito.Mockito.times(3)).save(payoutCaptor.capture());
        Instant failedAt = payoutCaptor.getAllValues().get(2).getFailedAt();
        org.junit.jupiter.api.Assertions.assertNotNull(failedAt);
        org.junit.jupiter.api.Assertions.assertFalse(failedAt.isBefore(before));
    }

    @Test
    void ignoresEventsAlreadyPresentInRedis() {
        UUID eventId = UUID.randomUUID();
        when(redisTemplate.hasKey("settlement:dedup:event:" + eventId)).thenReturn(true);

        service.handleSettlementRequested(envelope(eventId, payload("20.00", "USD")));

        verify(payoutRepository, never()).findByClaimId(any());
        verify(gatewayClient, never()).executePayout(any());
        verify(eventPublisher, never()).publishSettlementCompleted(any(), any(), any());
        verify(eventPublisher, never()).publishSettlementFailed(any(), any(), any());
    }

    @Test
    void marksMongoDuplicateAsProcessedWithoutExecutingPayout() {
        UUID eventId = UUID.randomUUID();
        when(processedRepository.existsById(eventId)).thenReturn(true);

        service.handleSettlementRequested(envelope(eventId, payload("20.00", "USD")));

        verify(payoutRepository, never()).findByClaimId(any());
        verify(gatewayClient, never()).executePayout(any());
    }

    @Test
    void doesNotRepeatAlreadySettledClaim() {
        UUID eventId = UUID.randomUUID();
        PayoutDocument payout = PayoutDocument.builder()
                .payoutId("pay-1").claimId("claim-1").status(PayoutStatus.SETTLED).build();
        when(payoutRepository.findByClaimId("claim-1")).thenReturn(Optional.of(payout));

        service.handleSettlementRequested(envelope(eventId, payload("20.00", "USD")));

        verify(gatewayClient, never()).executePayout(any());
        verify(eventPublisher, never()).publishSettlementCompleted(any(), any(), any());
        verify(processedRepository).save(any(ProcessedEventDocument.class));
    }

    @Test
    void retriesExistingFailedPayoutWithIncrementedAttemptCount() {
        PayoutDocument payout = PayoutDocument.builder()
                .payoutId("pay-1")
                .claimId("claim-1")
                .status(PayoutStatus.FAILED)
                .attemptCount(2)
                .build();
        when(payoutRepository.findByClaimId("claim-1")).thenReturn(Optional.of(payout));
        when(gatewayClient.executePayout(any(PayoutDocument.class))).thenReturn(
                new GatewayOutcome.Settled("provider-2", new BigDecimal("20.00"), "USD", Instant.now()));

        service.handleSettlementRequested(envelope(UUID.randomUUID(), payload("20.00", "USD")));

        var payoutCaptor = org.mockito.ArgumentCaptor.forClass(PayoutDocument.class);
        verify(gatewayClient).executePayout(payoutCaptor.capture());
        assertEquals(3, payoutCaptor.getValue().getAttemptCount());
        assertEquals(PayoutStatus.SETTLED, payoutCaptor.getValue().getStatus());
    }

    @Test
    void fallsBackToMongoWhenRedisDeduplicationCheckFails() {
        UUID eventId = UUID.randomUUID();
        when(redisTemplate.hasKey("settlement:dedup:event:" + eventId))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        when(processedRepository.existsById(eventId)).thenReturn(true);

        service.handleSettlementRequested(envelope(eventId, payload("20.00", "USD")));

        verify(processedRepository).existsById(eventId);
        verify(payoutRepository, never()).findByClaimId(any());
        verify(gatewayClient, never()).executePayout(any());
    }

    @Test
    void completesPayoutEvenIfRedisDeduplicationWriteFails() {
        when(payoutRepository.findByClaimId("claim-1")).thenReturn(Optional.empty());
        when(gatewayClient.executePayout(any(PayoutDocument.class))).thenReturn(
                new GatewayOutcome.Settled("provider-3", new BigDecimal("20.00"), "USD", Instant.now()));
        org.mockito.Mockito.doThrow(new IllegalStateException("Redis unavailable"))
                .when(redisValues)
                .set(any(String.class), any(String.class), any(java.time.Duration.class));

        service.handleSettlementRequested(envelope(UUID.randomUUID(), payload("20.00", "USD")));

        verify(eventPublisher).publishSettlementCompleted(any(), any(), any());
        verify(processedRepository).save(any(ProcessedEventDocument.class));
    }

    @Test
    void continuesPublishingWhenProcessedEventInsertIsConcurrentDuplicate() {
        when(payoutRepository.findByClaimId("claim-1")).thenReturn(Optional.empty());
        when(gatewayClient.executePayout(any(PayoutDocument.class))).thenReturn(
                new GatewayOutcome.Settled("provider-4", new BigDecimal("20.00"), "USD", Instant.now()));
        when(processedRepository.save(any(ProcessedEventDocument.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate event"));

        service.handleSettlementRequested(envelope(UUID.randomUUID(), payload("20.00", "USD")));

        verify(eventPublisher).publishSettlementCompleted(any(), any(), any());
        verify(redisValues).set(any(String.class), any(String.class), any(java.time.Duration.class));
    }

    @Test
    void normalizesMissingPayoutMoneyToZeroAndNullCurrencyForGateway() {
        when(payoutRepository.findByClaimId("claim-1")).thenReturn(Optional.empty());
        when(gatewayClient.executePayout(any(PayoutDocument.class))).thenAnswer(invocation -> {
            PayoutDocument payout = invocation.getArgument(0);
            assertEquals(new BigDecimal("0.00"), payout.getAmount());
            assertNull(payout.getCurrency());
            return new GatewayOutcome.Failed("missing amount", false, Instant.now());
        });
        SettlementRequestedPayload noAmount = new SettlementRequestedPayload(
                "claim-1", "pol-1", "holder-1", "stl-1", null, null, "LOW", Instant.now());

        service.handleSettlementRequested(envelope(UUID.randomUUID(), noAmount));

        verify(gatewayClient).executePayout(any(PayoutDocument.class));
        verify(eventPublisher).publishSettlementFailed(any(), any(), any());
    }

    @Test
    void defaultsMissingCausationIdToIncomingEventId() {
        UUID eventId = UUID.randomUUID();
        when(payoutRepository.findByClaimId("claim-1")).thenReturn(Optional.empty());
        when(gatewayClient.executePayout(any(PayoutDocument.class))).thenReturn(
                new GatewayOutcome.Failed("declined", false, Instant.now()));
        EventEnvelope<SettlementRequestedPayload> noCausation = new EventEnvelope<>(
                eventId, "SettlementRequested", "1.0", Instant.now(), "orchestrator",
                "corr-1", null, "claim-1", payload("20.00", "USD"));

        service.handleSettlementRequested(noCausation);

        var captor = org.mockito.ArgumentCaptor.forClass(PayoutDocument.class);
        verify(gatewayClient).executePayout(captor.capture());
        assertEquals(eventId.toString(), captor.getValue().getLastCausationId());
    }

    @Test
    void propagatesPayoutRepositoryFailureWithoutCallingGateway() {
        when(payoutRepository.findByClaimId("claim-1")).thenReturn(Optional.empty());
        when(payoutRepository.save(any(PayoutDocument.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.handleSettlementRequested(envelope(
                        UUID.randomUUID(), payload("20.00", "USD"))));

        verify(gatewayClient, never()).executePayout(any());
        verify(eventPublisher, never()).publishSettlementCompleted(any(), any(), any());
    }

    @Test
    void updatesExistingFailedPayoutWithNewAttemptCorrelation() {
        PayoutDocument payout = PayoutDocument.builder()
                .payoutId("pay-1").claimId("claim-1").status(PayoutStatus.FAILED)
                .attemptCount(1).build();
        when(payoutRepository.findByClaimId("claim-1")).thenReturn(Optional.of(payout));
        when(gatewayClient.executePayout(any(PayoutDocument.class))).thenReturn(
                new GatewayOutcome.Failed("still unavailable", false, Instant.now()));

        service.handleSettlementRequested(envelope(UUID.randomUUID(), payload("35.00", "CAD")));

        var captor = org.mockito.ArgumentCaptor.forClass(PayoutDocument.class);
        verify(gatewayClient).executePayout(captor.capture());
        assertEquals(2, captor.getValue().getAttemptCount());
        assertEquals("corr-1", captor.getValue().getLastCorrelationId());
        assertEquals(new BigDecimal("35.00"), captor.getValue().getAmount());
        assertEquals("CAD", captor.getValue().getCurrency());
    }

    private static SettlementRequestedPayload payload(String amount, String currency) {
        return new SettlementRequestedPayload(
                "claim-1", "pol-1", "holder-1", "stl-1",
                new Money(new BigDecimal(amount), currency), null, "LOW", Instant.now());
    }

    private static EventEnvelope<SettlementRequestedPayload> envelope(
            UUID eventId, SettlementRequestedPayload payload) {
        return new EventEnvelope<>(eventId, "SettlementRequested", "1.0", Instant.now(),
                "orchestrator", "corr-1", null, payload.claimId(), payload);
    }
}
