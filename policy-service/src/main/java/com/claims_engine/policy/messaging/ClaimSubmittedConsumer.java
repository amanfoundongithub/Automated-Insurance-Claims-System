package com.claims_engine.policy.messaging;

import com.claims_engine.policy.service.PolicyValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ClaimSubmittedConsumer {

    private static final Logger log = LoggerFactory.getLogger(ClaimSubmittedConsumer.class);

    private final PolicyValidationService policyValidationService;

    public ClaimSubmittedConsumer(PolicyValidationService policyValidationService) {
        this.policyValidationService = policyValidationService;
    }

    @RabbitListener(queues = "${claims.broker.claim-submitted-queue}")
    public void onClaimSubmitted(EventEnvelope<ClaimSubmittedPayload> envelope) {
        log.info("Received ClaimSubmitted in policy-service: eventId={} claimId={} policyId={}",
                envelope.eventId(),
                envelope.payload() != null ? envelope.payload().claimId() : null,
                envelope.payload() != null ? envelope.payload().policyId() : null);
        policyValidationService.validateClaim(envelope);
    }
}