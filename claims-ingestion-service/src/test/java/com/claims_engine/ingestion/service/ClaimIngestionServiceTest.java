package com.claims_engine.ingestion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.claims_engine.ingestion.api.ClaimResponse;
import com.claims_engine.ingestion.api.SubmitClaimRequest;
import com.claims_engine.ingestion.domain.ClaimDocument;
import com.claims_engine.ingestion.domain.ClaimRepository;
import com.claims_engine.ingestion.domain.ClaimStatus;
import com.claims_engine.ingestion.domain.Money;
import com.claims_engine.ingestion.messaging.ClaimSubmittedPayload;
import com.claims_engine.ingestion.outbox.OutboxEventDocument;
import com.claims_engine.ingestion.outbox.OutboxRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ClaimIngestionServiceTest {

    private ClaimRepository claimRepository;
    private OutboxRepository outboxRepository;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> redisValues;
    private ClaimIngestionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        claimRepository = org.mockito.Mockito.mock(ClaimRepository.class);
        outboxRepository = org.mockito.Mockito.mock(OutboxRepository.class);
        redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        redisValues = org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(redisValues);
        service = new ClaimIngestionService(
                claimRepository, outboxRepository, redisTemplate, "ingestion", "claims", "claim.submitted", 24);
    }

    @Test
    void createsClaimAndOutboxEventForNewIdempotencyKey() {
        when(redisValues.get("idem:claim:key-1")).thenReturn(null);
        when(claimRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(claimRepository.save(any(ClaimDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        SubmitClaimRequest request = request(Map.of("location", "north"), null);

        ClaimResponse response = service.submitClaim(request, "key-1", "corr-1");

        assertNotNull(response.claimId());
        assertEquals(ClaimStatus.SUBMITTED, response.status());
        assertEquals("corr-1", response.correlationId());
        assertNotNull(response.eventId());
        verify(outboxRepository).save(any(OutboxEventDocument.class));
        verify(redisValues).set("idem:claim:key-1", response.claimId(), Duration.ofHours(24));
    }

    @Test
    void returnsRedisCachedClaimWithoutCreatingAnotherEvent() {
        ClaimDocument existing = existingClaim("clm_cached", "corr-existing");
        when(redisValues.get("idem:claim:key-1")).thenReturn("clm_cached");
        when(claimRepository.findById("clm_cached")).thenReturn(Optional.of(existing));

        ClaimResponse response = service.submitClaim(request(null, null), "key-1", "corr-new");

        assertEquals("clm_cached", response.claimId());
        assertEquals("corr-existing", response.correlationId());
        assertNull(response.eventId());
        verify(claimRepository, never()).findByIdempotencyKey(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void recoversFromDatabaseIdempotencyRecordAndRefreshesRedis() {
        ClaimDocument existing = existingClaim("clm_existing", "corr-old");
        when(redisValues.get("idem:claim:key-1")).thenReturn(null);
        when(claimRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        ClaimResponse response = service.submitClaim(request(null, null), "key-1", "corr-new");

        assertEquals("clm_existing", response.claimId());
        assertNull(response.eventId());
        verify(redisValues).set("idem:claim:key-1", "clm_existing", Duration.ofHours(24));
        verify(claimRepository, never()).save(any());
    }

    @Test
    void normalizesOptionalDetailsAndAttachmentsToEmptyCollections() {
        when(redisValues.get("idem:claim:key-1")).thenReturn(null);
        when(claimRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(claimRepository.save(any(ClaimDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.submitClaim(request(null, null), "key-1", "corr-1");

        var claimCaptor = org.mockito.ArgumentCaptor.forClass(ClaimDocument.class);
        verify(claimRepository).save(claimCaptor.capture());
        assertEquals(Map.of(), claimCaptor.getValue().getIncidentDetails());
        assertEquals(java.util.List.of(), claimCaptor.getValue().getAttachments());
        var outboxCaptor = org.mockito.ArgumentCaptor.forClass(OutboxEventDocument.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        ClaimSubmittedPayload payload = outboxCaptor.getValue().getEnvelope().payload();
        assertEquals(Map.of(), payload.incidentDetails());
        assertEquals(java.util.List.of(), payload.attachments());
    }

    @Test
    void handlesConcurrentDuplicateByReturningTheWinningClaim() {
        ClaimDocument existing = existingClaim("clm_winner", "corr-winner");
        when(redisValues.get("idem:claim:key-1")).thenReturn(null);
        when(claimRepository.findByIdempotencyKey("key-1"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(claimRepository.save(any(ClaimDocument.class)))
                .thenThrow(new DuplicateKeyException("duplicate idempotency key"));

        ClaimResponse response = service.submitClaim(request(null, null), "key-1", "corr-new");

        assertEquals("clm_winner", response.claimId());
        assertNull(response.eventId());
        verify(outboxRepository, never()).save(any());
        verify(redisValues).set("idem:claim:key-1", "clm_winner", Duration.ofHours(24));
    }

    @Test
    void repairsStaleRedisPointerFromDatabaseIdempotencyRecord() {
        ClaimDocument existing = existingClaim("clm_recovered", "corr-existing");
        when(redisValues.get("idem:claim:key-1")).thenReturn("clm_stale");
        when(claimRepository.findById("clm_stale")).thenReturn(Optional.empty());
        when(claimRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        ClaimResponse response = service.submitClaim(request(null, null), "key-1", "corr-new");

        assertEquals("clm_recovered", response.claimId());
        assertNull(response.eventId());
        verify(redisValues).set("idem:claim:key-1", "clm_recovered", Duration.ofHours(24));
        verify(claimRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void rethrowsDuplicateKeyWhenWinningRecordCannotBeRecovered() {
        when(redisValues.get("idem:claim:key-1")).thenReturn(null);
        when(claimRepository.findByIdempotencyKey("key-1"))
                .thenReturn(Optional.empty(), Optional.empty());
        when(claimRepository.save(any(ClaimDocument.class)))
                .thenThrow(new DuplicateKeyException("duplicate idempotency key"));

        assertThrows(
                DuplicateKeyException.class,
                () -> service.submitClaim(request(null, null), "key-1", "corr-1"));

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void createsNewClaimWhenRedisPointerIsStaleAndDatabaseHasNoRecord() {
        when(redisValues.get("idem:claim:key-1")).thenReturn("clm_stale");
        when(claimRepository.findById("clm_stale")).thenReturn(Optional.empty());
        when(claimRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(claimRepository.save(any(ClaimDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ClaimResponse response = service.submitClaim(request(null, null), "key-1", "corr-1");

        assertNotNull(response.eventId());
        org.junit.jupiter.api.Assertions.assertNotEquals("clm_stale", response.claimId());
        verify(outboxRepository).save(any(OutboxEventDocument.class));
    }

    @Test
    void writesConfiguredEventMetadataAndClaimDetailsToOutbox() {
        Map<String, Object> details = Map.of("location", "north", "severity", "major");
        when(redisValues.get("idem:claim:key-1")).thenReturn(null);
        when(claimRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(claimRepository.save(any(ClaimDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.submitClaim(request(details, null), "key-1", "corr-1");

        var captor = org.mockito.ArgumentCaptor.forClass(OutboxEventDocument.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEventDocument event = captor.getValue();
        assertEquals("claims", event.getExchange());
        assertEquals("claim.submitted", event.getRoutingKey());
        assertEquals("ClaimSubmitted", event.getEventType());
        assertEquals("PENDING", event.getStatus().name());
        assertEquals(details, event.getEnvelope().payload().incidentDetails());
        assertEquals("ingestion", event.getEnvelope().producer());
        assertEquals("corr-1", event.getEnvelope().correlationId());
    }

    @Test
    void propagatesClaimPersistenceFailureWithoutCreatingOutboxOrCacheEntry() {
        when(redisValues.get("idem:claim:key-1")).thenReturn(null);
        when(claimRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(claimRepository.save(any(ClaimDocument.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(
                IllegalStateException.class,
                () -> service.submitClaim(request(null, null), "key-1", "corr-1"));

        verify(outboxRepository, never()).save(any());
        verify(redisValues, never()).set(any(), any(), any(java.time.Duration.class));
    }

    @Test
    void propagatesOutboxPersistenceFailureWithoutCachingClaim() {
        when(redisValues.get("idem:claim:key-1")).thenReturn(null);
        when(claimRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(claimRepository.save(any(ClaimDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(outboxRepository.save(any(OutboxEventDocument.class)))
                .thenThrow(new IllegalStateException("outbox unavailable"));

        assertThrows(
                IllegalStateException.class,
                () -> service.submitClaim(request(null, null), "key-1", "corr-1"));

        verify(redisValues, never()).set(any(), any(), any(java.time.Duration.class));
    }

    @Test
    void distinctIdempotencyKeysCreateDistinctClaims() {
        when(claimRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(claimRepository.save(any(ClaimDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ClaimResponse first = service.submitClaim(request(null, null), "key-1", "corr-1");
        ClaimResponse second = service.submitClaim(request(null, null), "key-2", "corr-2");

        org.junit.jupiter.api.Assertions.assertNotEquals(first.claimId(), second.claimId());
        org.junit.jupiter.api.Assertions.assertNotEquals(first.eventId(), second.eventId());
        verify(outboxRepository, org.mockito.Mockito.times(2)).save(any(OutboxEventDocument.class));
    }

    private static SubmitClaimRequest request(Map<String, Object> details, java.util.List<com.claims_engine.ingestion.domain.AttachmentItem> attachments) {
        return new SubmitClaimRequest(
                "pol-1", "holder-1", "AUTO", Instant.parse("2025-01-01T00:00:00Z"),
                new Money(new BigDecimal("125.00"), "USD"), details, attachments);
    }

    private static ClaimDocument existingClaim(String claimId, String correlationId) {
        ClaimDocument claim = new ClaimDocument();
        claim.setClaimId(claimId);
        claim.setPolicyId("pol-1");
        claim.setStatus(ClaimStatus.SUBMITTED);
        claim.setCorrelationId(correlationId);
        claim.setReportedAt(Instant.parse("2025-01-02T00:00:00Z"));
        return claim;
    }
}
