package com.claims_engine.policy.service;

import com.claims_engine.policy.domain.PolicyDocument;
import com.claims_engine.policy.domain.PolicyRepository;
import com.claims_engine.policy.domain.PolicyStatus;
import com.claims_engine.policy.domain.ProcessedEventDocument;
import com.claims_engine.policy.domain.ProcessedEventRepository;
import com.claims_engine.policy.messaging.ClaimSubmittedPayload;
import com.claims_engine.policy.messaging.EventEnvelope;
import com.claims_engine.policy.messaging.PolicyValidatedPayload;
import com.claims_engine.policy.messaging.PolicyValidationFailedPayload;
import com.claims_engine.policy.outbox.OutboxEventDocument;
import com.claims_engine.policy.outbox.OutboxRepository;
import com.claims_engine.policy.outbox.OutboxStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyValidationService {

    private static final Logger log = LoggerFactory.getLogger(PolicyValidationService.class);

    private final PolicyRepository policyRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxRepository outboxRepository;
    private final StringRedisTemplate redisTemplate;
    private final String policyValidatedRoutingKey;
    private final String policyValidationFailedRoutingKey;
    private final long dedupTtlHours;

    public PolicyValidationService(
            PolicyRepository policyRepository,
            ProcessedEventRepository processedEventRepository,
            OutboxRepository outboxRepository,
            StringRedisTemplate redisTemplate,
            @Value("${claims.broker.policy-validated-routing-key}") String policyValidatedRoutingKey,
            @Value("${claims.broker.policy-validation-failed-routing-key}") String policyValidationFailedRoutingKey,
            @Value("${claims.dedup.redis-ttl-hours:72}") long dedupTtlHours
    ) {
        this.policyRepository = policyRepository;
        this.processedEventRepository = processedEventRepository;
        this.outboxRepository = outboxRepository;
        this.redisTemplate = redisTemplate;
        this.policyValidatedRoutingKey = policyValidatedRoutingKey;
        this.policyValidationFailedRoutingKey = policyValidationFailedRoutingKey;
        this.dedupTtlHours = dedupTtlHours;
    }

    @Transactional
    public void validateClaim(EventEnvelope<ClaimSubmittedPayload> envelope) {
        UUID incomingEventId = envelope.eventId();
        ClaimSubmittedPayload payload = envelope.payload();
        String claimId = payload.claimId();
        String policyId = payload.policyId();
        String redisKey = "policy:dedup:event:" + incomingEventId;

        if (Boolean.TRUE.equals(redisTemplate.hasKey(redisKey)) || processedEventRepository.existsById(incomingEventId)) {
            log.info("Duplicate ClaimSubmitted ignored in policy-service: eventId={} claimId={}",
                    incomingEventId, claimId);
            redisTemplate.opsForValue().set(redisKey, "1", Duration.ofHours(dedupTtlHours));
            return;
        }

        List<String> failures = new ArrayList<>();
        Optional<PolicyDocument> policyOpt = policyRepository.findById(policyId);

        PolicyDocument policy = null;
        if (policyOpt.isEmpty()) {
            failures.add("POLICY_NOT_FOUND: Policy " + policyId + " does not exist");
        } else {
            policy = policyOpt.get();

            if (policy.getStatus() != PolicyStatus.ACTIVE) {
                failures.add("POLICY_INACTIVE: Policy status is " + policy.getStatus());
            }

            Instant incidentAt = payload.incidentOccurredAt();
            if (incidentAt != null && policy.getEffectiveFrom() != null && incidentAt.isBefore(policy.getEffectiveFrom())) {
                failures.add("BEFORE_EFFECTIVE_DATE: Incident occurred before policy effectiveFrom");
            }
            if (incidentAt != null && policy.getEffectiveTo() != null && incidentAt.isAfter(policy.getEffectiveTo())) {
                failures.add("AFTER_EXPIRY_DATE: Incident occurred after policy effectiveTo");
            }

            if (policy.getEligibleClaimTypes() == null || !policy.getEligibleClaimTypes().contains(payload.claimType())) {
                failures.add("CLAIM_TYPE_NOT_COVERED: Claim type " + payload.claimType() + " is not eligible");
            }

            if (payload.claimedAmount() != null && policy.getMaxCoverageAmount() != null) {
                String claimCurrency = payload.claimedAmount().currency();
                String maxCurrency = policy.getMaxCoverageAmount().currency();
                if (!claimCurrency.equals(maxCurrency)) {
                    failures.add("CURRENCY_MISMATCH: Claim currency " + claimCurrency + " != policy currency " + maxCurrency);
                } else if (payload.claimedAmount().amount().compareTo(policy.getMaxCoverageAmount().amount()) > 0) {
                    failures.add("EXCEEDS_MAX_COVERAGE: Claimed amount " + payload.claimedAmount().amount()
                            + " exceeds max coverage " + policy.getMaxCoverageAmount().amount());
                }
            }
        }

        OutboxEventDocument outboxEvent = new OutboxEventDocument();
        outboxEvent.setEventId(UUID.randomUUID());
        outboxEvent.setAggregateType("Claim");
        outboxEvent.setAggregateId(claimId);
        outboxEvent.setEventVersion("v1");
        outboxEvent.setCorrelationId(envelope.correlationId());
        outboxEvent.setCausationId(incomingEventId.toString());
        outboxEvent.setStatus(OutboxStatus.PENDING);
        outboxEvent.setRetryCount(0);

        if (failures.isEmpty()) {
            PolicyValidatedPayload validatedPayload = new PolicyValidatedPayload(
                    claimId,
                    policyId,
                    payload.policyholderId(),
                    policy.getMaxCoverageAmount(),
                    policy.getDeductibleAmount(),
                    policy.getCoPayRatio(),
                    Instant.now()
            );
            outboxEvent.setEventType("PolicyValidated");
            outboxEvent.setRoutingKey(policyValidatedRoutingKey);
            outboxEvent.setPayload(toValidatedPayloadMap(validatedPayload));
        } else {
            PolicyValidationFailedPayload failedPayload = new PolicyValidationFailedPayload(
                    claimId,
                    policyId,
                    payload.policyholderId(),
                    failures,
                    Instant.now()
            );
            outboxEvent.setEventType("PolicyValidationFailed");
            outboxEvent.setRoutingKey(policyValidationFailedRoutingKey);
            outboxEvent.setPayload(toFailedPayloadMap(failedPayload));
        }

        ProcessedEventDocument processed = new ProcessedEventDocument();
        processed.setEventId(incomingEventId);
        processed.setClaimId(claimId);
        processed.setPolicyId(policyId);

        try {
            processedEventRepository.save(processed);
            outboxRepository.save(outboxEvent);
            redisTemplate.opsForValue().set(redisKey, "1", Duration.ofHours(dedupTtlHours));

            log.info("Evaluated policy for claimId={}: outcome={} outboxEventId={}",
                    claimId, outboxEvent.getEventType(), outboxEvent.getEventId());
        } catch (DuplicateKeyException dup) {
            log.info("Concurrent duplicate eventId caught in policy-service: eventId={} claimId={}",
                    incomingEventId, claimId);
            redisTemplate.opsForValue().set(redisKey, "1", Duration.ofHours(dedupTtlHours));
        }
    }

    private Map<String, Object> toValidatedPayloadMap(PolicyValidatedPayload p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("claimId", p.claimId());
        map.put("policyId", p.policyId());
        map.put("policyholderId", p.policyholderId());
        map.put("maxCoverageAmount", Map.of(
                "amount", p.maxCoverageAmount().amount(),
                "currency", p.maxCoverageAmount().currency()
        ));
        map.put("deductibleAmount", Map.of(
                "amount", p.deductibleAmount().amount(),
                "currency", p.deductibleAmount().currency()
        ));
        map.put("coPayRatio", p.coPayRatio());
        map.put("validatedAt", p.validatedAt().toString());
        return map;
    }

    private Map<String, Object> toFailedPayloadMap(PolicyValidationFailedPayload p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("claimId", p.claimId());
        map.put("policyId", p.policyId());
        map.put("policyholderId", p.policyholderId());
        map.put("reasons", p.reasons());
        map.put("evaluatedAt", p.evaluatedAt().toString());
        return map;
    }
} 