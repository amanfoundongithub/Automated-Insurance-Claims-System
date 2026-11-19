package com.claims_engine.orchestrator.messaging;

import com.claims_engine.orchestrator.service.ClaimOrchestrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PolicyEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PolicyEventConsumer.class);

    private final ClaimOrchestrationService orchestrationService;

    public PolicyEventConsumer(ClaimOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @RabbitListener(queues = "${claims.broker.policy-validated-queue:q.orchestrator.policy-validated}")
    public void onPolicyValidated(EventEnvelope<PolicyValidatedPayload> envelope) {
        log.info("Received PolicyValidated in orchestrator: eventId={} claimId={}",
                envelope.eventId(),
                envelope.payload() != null ? envelope.payload().claimId() : null);
        orchestrationService.handlePolicyValidated(envelope);
    }

    @RabbitListener(queues = "${claims.broker.policy-validation-failed-queue:q.orchestrator.policy-validation-failed}")
    public void onPolicyValidationFailed(EventEnvelope<PolicyValidationFailedPayload> envelope) {
        log.info("Received PolicyValidationFailed in orchestrator: eventId={} claimId={}",
                envelope.eventId(),
                envelope.payload() != null ? envelope.payload().claimId() : null);
        orchestrationService.handlePolicyValidationFailed(envelope);
    }
}