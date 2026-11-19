package com.claims_engine.policy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.claims_engine.policy.domain.Money;
import com.claims_engine.policy.domain.PolicyDocument;
import com.claims_engine.policy.domain.PolicyRepository;
import com.claims_engine.policy.domain.PolicyStatus;
import com.claims_engine.policy.domain.ProcessedEventDocument;
import com.claims_engine.policy.domain.ProcessedEventRepository;
import com.claims_engine.policy.messaging.ClaimSubmittedPayload;
import com.claims_engine.policy.messaging.EventEnvelope;
import com.claims_engine.policy.outbox.OutboxEventDocument;
import com.claims_engine.policy.outbox.OutboxRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class PolicyValidationServiceTest {

    private PolicyRepository policyRepository;
    private ProcessedEventRepository processedRepository;
    private OutboxRepository outboxRepository;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> redisValues;
    private PolicyValidationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        policyRepository = org.mockito.Mockito.mock(PolicyRepository.class);
        processedRepository = org.mockito.Mockito.mock(ProcessedEventRepository.class);
        outboxRepository = org.mockito.Mockito.mock(OutboxRepository.class);
        redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        redisValues = org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(redisValues);
        when(redisTemplate.hasKey(any())).thenReturn(false);
        service = new PolicyValidationService(
                policyRepository, processedRepository, outboxRepository, redisTemplate,
                "policy.validated", "policy.failed", 72);
    }

    @Test
    void emitsValidatedEventForCoveredClaim() {
        UUID eventId = UUID.randomUUID();
        when(policyRepository.findById("pol-1")).thenReturn(Optional.of(activePolicy()));

        service.validateClaim(envelope(eventId, payload("AUTO", "USD", "250.00", Instant.parse("2025-05-01T00:00:00Z"))));

        var captor = org.mockito.ArgumentCaptor.forClass(OutboxEventDocument.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEventDocument event = captor.getValue();
        assertEquals("PolicyValidated", event.getEventType());
        assertEquals("policy.validated", event.getRoutingKey());
        assertEquals("claim-1", event.getPayload().get("claimId"));
        verify(processedRepository).save(any(ProcessedEventDocument.class));
        verify(redisValues).set("policy:dedup:event:" + eventId, "1", Duration.ofHours(72));
    }

    @Test
    void emitsFailureWhenPolicyDoesNotExist() {
        when(policyRepository.findById("pol-1")).thenReturn(Optional.empty());

        service.validateClaim(envelope(UUID.randomUUID(), payload("AUTO", "USD", "250.00", null)));

        var captor = org.mockito.ArgumentCaptor.forClass(OutboxEventDocument.class);
        verify(outboxRepository).save(captor.capture());
        assertEquals("PolicyValidationFailed", captor.getValue().getEventType());
        assertEquals(List.of("POLICY_NOT_FOUND: Policy pol-1 does not exist"),
                captor.getValue().getPayload().get("reasons"));
    }

    @Test
    void accumulatesInactiveDateAndCoverageTypeFailures() {
        PolicyDocument policy = activePolicy();
        policy.setStatus(PolicyStatus.SUSPENDED);
        policy.setEffectiveFrom(Instant.parse("2025-06-01T00:00:00Z"));
        policy.setEffectiveTo(Instant.parse("2025-06-30T00:00:00Z"));
        policy.setEligibleClaimTypes(Set.of("HOME"));
        when(policyRepository.findById("pol-1")).thenReturn(Optional.of(policy));

        service.validateClaim(envelope(
                UUID.randomUUID(), payload("AUTO", "USD", "250.00", Instant.parse("2025-05-01T00:00:00Z"))));

        var captor = org.mockito.ArgumentCaptor.forClass(OutboxEventDocument.class);
        verify(outboxRepository).save(captor.capture());
        List<?> reasons = (List<?>) captor.getValue().getPayload().get("reasons");
        assertEquals(3, reasons.size());
        org.junit.jupiter.api.Assertions.assertTrue(reasons.toString().contains("POLICY_INACTIVE"));
        org.junit.jupiter.api.Assertions.assertTrue(reasons.toString().contains("BEFORE_EFFECTIVE_DATE"));
        org.junit.jupiter.api.Assertions.assertTrue(reasons.toString().contains("CLAIM_TYPE_NOT_COVERED"));
    }

    @Test
    void rejectsClaimsOverCoverageAndCurrencyMismatch() {
        PolicyDocument policy = activePolicy();
        when(policyRepository.findById("pol-1")).thenReturn(Optional.of(policy));
        service.validateClaim(envelope(
                UUID.randomUUID(), payload("AUTO", "USD", "1000.00", Instant.parse("2025-05-01T00:00:00Z"))));
        service.validateClaim(envelope(
                UUID.randomUUID(), payload("AUTO", "CAD", "100.00", Instant.parse("2025-05-01T00:00:00Z"))));

        var captor = org.mockito.ArgumentCaptor.forClass(OutboxEventDocument.class);
        verify(outboxRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertEquals("PolicyValidationFailed", captor.getAllValues().get(0).getEventType());
        assertEquals(List.of("EXCEEDS_MAX_COVERAGE: Claimed amount 1000.00 exceeds max coverage 500.00"),
                captor.getAllValues().get(0).getPayload().get("reasons"));
        assertEquals(List.of("CURRENCY_MISMATCH: Claim currency CAD != policy currency USD"),
                captor.getAllValues().get(1).getPayload().get("reasons"));
    }

    @Test
    void skipsEventsAlreadyRecordedInRedisOrMongo() {
        UUID eventId = UUID.randomUUID();
        when(redisTemplate.hasKey("policy:dedup:event:" + eventId)).thenReturn(true);

        service.validateClaim(envelope(eventId, payload("AUTO", "USD", "250.00", null)));

        verify(policyRepository, never()).findById(any());
        verify(processedRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
        verify(redisValues).set("policy:dedup:event:" + eventId, "1", Duration.ofHours(72));
    }

    @Test
    void handlesConcurrentDuplicateInsertWithoutCreatingOutbox() {
        UUID eventId = UUID.randomUUID();
        when(policyRepository.findById("pol-1")).thenReturn(Optional.of(activePolicy()));
        when(processedRepository.save(any(ProcessedEventDocument.class)))
                .thenThrow(new DuplicateKeyException("duplicate event"));

        service.validateClaim(envelope(eventId, payload("AUTO", "USD", "250.00", null)));

        verify(outboxRepository, never()).save(any());
        verify(redisValues).set("policy:dedup:event:" + eventId, "1", Duration.ofHours(72));
    }

    private static PolicyDocument activePolicy() {
        PolicyDocument policy = new PolicyDocument();
        policy.setPolicyId("pol-1");
        policy.setStatus(PolicyStatus.ACTIVE);
        policy.setEffectiveFrom(Instant.parse("2025-01-01T00:00:00Z"));
        policy.setEffectiveTo(Instant.parse("2025-12-31T23:59:59Z"));
        policy.setEligibleClaimTypes(Set.of("AUTO"));
        policy.setMaxCoverageAmount(new Money(new BigDecimal("500.00"), "USD"));
        policy.setDeductibleAmount(new Money(new BigDecimal("50.00"), "USD"));
        policy.setCoPayRatio(new BigDecimal("0.10"));
        return policy;
    }

    private static ClaimSubmittedPayload payload(String type, String currency, String amount, Instant incidentAt) {
        return new ClaimSubmittedPayload(
                "claim-1", "pol-1", "holder-1", type, incidentAt, Instant.now(),
                new Money(new BigDecimal(amount), currency), Map.of(), List.of());
    }

    private static EventEnvelope<ClaimSubmittedPayload> envelope(UUID eventId, ClaimSubmittedPayload payload) {
        return new EventEnvelope<>(eventId, "ClaimSubmitted", "1.0", Instant.now(),
                "ingestion", "corr-1", null, payload.claimId(), payload);
    }
}
