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
import java.time.Duration;
import java.time.Instant;
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
    private final StringRedisTemplate redisTemplate;
    private final long dedupTtlHours;

    public ClaimOrchestrationService(
            ClaimLifecycleRepository lifecycleRepository,
            ClaimAuditLogRepository auditLogRepository,
            ProcessedEventRepository processedEventRepository,
            StringRedisTemplate redisTemplate,
            @Value("${claims.dedup.redis-ttl-hours:72}") long dedupTtlHours
    ) {
        this.lifecycleRepository = lifecycleRepository;
        this.auditLogRepository = auditLogRepository;
        this.processedEventRepository = processedEventRepository;
        this.redisTemplate = redisTemplate;
        this.dedupTtlHours = dedupTtlHours;
    }

    @Transactional
    public void handleClaimSubmitted(EventEnvelope<ClaimSubmittedPayload> envelope) {
        UUID eventId = envelope.eventId();
        ClaimSubmittedPayload payload = envelope.payload();
        String claimId = payload.claimId();
        String redisKey = "orch:dedup:event:" + eventId;

        if (Boolean.TRUE.equals(redisTemplate.hasKey(redisKey)) || processedEventRepository.existsById(eventId)) {
            log.info("Duplicate event ignored: eventId={} claimId={}", eventId, claimId);
            redisTemplate.opsForValue().set(redisKey, "1", Duration.ofHours(dedupTtlHours));
            return;
        }

        ClaimLifecycleDocument lifecycle = lifecycleRepository.findById(claimId).orElse(null);
        ClaimLifecycleStatus previousStatus = lifecycle != null ? lifecycle.getStatus() : null;
        ClaimLifecycleStatus nextStatus = ClaimLifecycleStatus.SUBMITTED;

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
        }

        lifecycle.setStatus(nextStatus);
        lifecycle.setCorrelationId(envelope.correlationId());
        lifecycle.setLastEventType(envelope.eventType());

        ClaimAuditLogDocument auditEntry = new ClaimAuditLogDocument();
        auditEntry.setAuditId("aud_" + UUID.randomUUID().toString().replace("-", ""));
        auditEntry.setClaimId(claimId);
        auditEntry.setEventId(eventId);
        auditEntry.setEventType(envelope.eventType());
        auditEntry.setPreviousStatus(previousStatus);
        auditEntry.setNewStatus(nextStatus);
        auditEntry.setCorrelationId(envelope.correlationId());
        auditEntry.setCausationId(envelope.causationId());
        auditEntry.setNotes("Claim ingested and registered in orchestrator");
        auditEntry.setOccurredAt(envelope.occurredAt() != null ? envelope.occurredAt() : Instant.now());

        ProcessedEventDocument processedEvent = new ProcessedEventDocument();
        processedEvent.setEventId(eventId);
        processedEvent.setClaimId(claimId);
        processedEvent.setEventType(envelope.eventType());
        processedEvent.setCorrelationId(envelope.correlationId());

        try {
            lifecycleRepository.save(lifecycle);
            auditLogRepository.save(auditEntry);
            processedEventRepository.save(processedEvent);
            redisTemplate.opsForValue().set(redisKey, "1", Duration.ofHours(dedupTtlHours));

            log.info("Orchestrated ClaimSubmitted: claimId={} eventId={} status={} correlationId={}",
                    claimId, eventId, nextStatus, envelope.correlationId());
        } catch (DuplicateKeyException dup) {
            log.info("Concurrent duplicate eventId caught on insert: eventId={} claimId={}", eventId, claimId);
            redisTemplate.opsForValue().set(redisKey, "1", Duration.ofHours(dedupTtlHours));
        }
    }
}