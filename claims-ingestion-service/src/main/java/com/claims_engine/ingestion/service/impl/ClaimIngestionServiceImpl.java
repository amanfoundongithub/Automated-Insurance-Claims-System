package com.claims_engine.ingestion.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.claims_engine.ingestion.api.ClaimResponse;
import com.claims_engine.ingestion.api.SubmitClaimRequest;
import com.claims_engine.ingestion.domain.AttachmentItem;
import com.claims_engine.ingestion.domain.ClaimDocument;
import com.claims_engine.ingestion.domain.ClaimRepository;
import com.claims_engine.ingestion.domain.ClaimStatus;
import com.claims_engine.ingestion.messaging.ClaimSubmittedPayload;
import com.claims_engine.ingestion.messaging.EventEnvelope;
import com.claims_engine.ingestion.outbox.OutboxEventDocument;
import com.claims_engine.ingestion.outbox.OutboxRepository;
import com.claims_engine.ingestion.outbox.OutboxStatus;
import com.claims_engine.ingestion.service.ClaimIngestionService;
import com.mongodb.DuplicateKeyException;

@Service 
public class ClaimIngestionServiceImpl implements ClaimIngestionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClaimIngestionServiceImpl.class);

    private final ClaimRepository claimRepository;
    private final OutboxRepository outboxRepository;
    private final StringRedisTemplate redisTemplate;
    private final String producerName;
    private final String exchangeName;
    private final String claimSubmittedRoutingKey;
    private final long idempotencyTtlHours;

    public ClaimIngestionServiceImpl(
            ClaimRepository claimRepository,
            OutboxRepository outboxRepository,
            StringRedisTemplate redisTemplate,
            @Value ("${spring.application.name:ingestion}") String producerName,
            @Value("${claims.broker.exchange}") String exchangeName,
            @Value("${claims.broker.claim-submitted-routing-key}") String claimSubmittedRoutingKey,
            @Value("${claims.idempotency.redis-ttl-hours:24}") long idempotencyTtlHours
    ) {
        this.claimRepository = claimRepository;
        this.outboxRepository = outboxRepository;
        this.redisTemplate = redisTemplate;
        this.producerName = producerName;
        this.exchangeName = exchangeName;
        this.claimSubmittedRoutingKey = claimSubmittedRoutingKey;
        this.idempotencyTtlHours = idempotencyTtlHours;
    }

    @Transactional
    public ClaimResponse submitClaim(
            SubmitClaimRequest request,
            String idempotencyKey,
            String correlationId
    ) {
        String redisKey = "idem:claim:" + idempotencyKey;
        String existingClaimId = redisTemplate.opsForValue().get(redisKey);
        if (existingClaimId != null) {
            return claimRepository.findById(existingClaimId)
                    .map(existing -> toResponse(existing, null))
                    .orElseGet(() -> findByDbIdempotencyKeyOrCreate(request, idempotencyKey, correlationId, redisKey));
        }

        return findByDbIdempotencyKeyOrCreate(request, idempotencyKey, correlationId, redisKey);
    }

    private ClaimResponse findByDbIdempotencyKeyOrCreate(
            SubmitClaimRequest request,
            String idempotencyKey,
            String correlationId,
            String redisKey
    ) {
        var existingOpt = claimRepository.findByIdempotencyKey(idempotencyKey);
        if (existingOpt.isPresent()) {
            ClaimDocument existing = existingOpt.get();
            redisTemplate.opsForValue().set(redisKey, existing.getClaimId(), Duration.ofHours(idempotencyTtlHours));
            return toResponse(existing, null);
        }

        Instant now = Instant.now();
        String claimId = "clm_" + UUID.randomUUID().toString().replace("-", "");
        UUID eventId = UUID.randomUUID();
        List<AttachmentItem> safeAttachments = request.attachments() != null ? request.attachments() : List.of();
        Map<String, Object> safeIncidentDetails = request.incidentDetails() != null ? request.incidentDetails() : Map.of();

        ClaimDocument claim = new ClaimDocument();
        claim.setClaimId(claimId);
        claim.setIdempotencyKey(idempotencyKey);
        claim.setPolicyId(request.policyId());
        claim.setPolicyholderId(request.policyholderId());
        claim.setClaimType(request.claimType());
        claim.setIncidentOccurredAt(request.incidentOccurredAt());
        claim.setReportedAt(now);
        claim.setClaimedAmount(request.claimedAmount());
        claim.setIncidentDetails(safeIncidentDetails);
        claim.setAttachments(safeAttachments);
        claim.setStatus(ClaimStatus.SUBMITTED);
        claim.setCorrelationId(correlationId);

        ClaimSubmittedPayload payload = new ClaimSubmittedPayload(
                claimId,
                request.policyId(),
                request.policyholderId(),
                request.claimType(),
                request.incidentOccurredAt(),
                now,
                request.claimedAmount(),
                safeIncidentDetails,
                safeAttachments
        );

        EventEnvelope<ClaimSubmittedPayload> envelope = new EventEnvelope<>(
                eventId,
                EVENT_TYPE_CLAIM_SUBMITTED,
                EVENT_VERSION_V1,
                now,
                producerName,
                correlationId,
                idempotencyKey,
                claimId,
                payload
        );

        OutboxEventDocument outboxEvent = new OutboxEventDocument();
        outboxEvent.setEventId(eventId);
        outboxEvent.setAggregateId(claimId);
        outboxEvent.setEventType(EVENT_TYPE_CLAIM_SUBMITTED);
        outboxEvent.setExchange(exchangeName);
        outboxEvent.setRoutingKey(claimSubmittedRoutingKey);
        outboxEvent.setEnvelope(envelope);
        outboxEvent.setStatus(OutboxStatus.PENDING);

        try {
            ClaimDocument savedClaim = claimRepository.save(claim);
            outboxRepository.save(outboxEvent);
            redisTemplate.opsForValue().set(redisKey, savedClaim.getClaimId(), Duration.ofHours(idempotencyTtlHours));
            return toResponse(savedClaim, eventId);
        } catch (DuplicateKeyException dup) {
            ClaimDocument existing = claimRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> dup);
            redisTemplate.opsForValue().set(redisKey, existing.getClaimId(), Duration.ofHours(idempotencyTtlHours));
            return toResponse(existing, null);
        }
    }

    private ClaimResponse toResponse(ClaimDocument claim, UUID eventId) {
        return new ClaimResponse(
                claim.getClaimId(),
                claim.getPolicyId(),
                claim.getStatus(),
                eventId,
                claim.getCorrelationId(),
                claim.getReportedAt()
        );
    }
    
}
