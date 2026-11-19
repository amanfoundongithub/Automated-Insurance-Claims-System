package com.claims_engine.orchestrator.messaging;

import com.claims_engine.orchestrator.service.ClaimOrchestrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class RiskEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RiskEventConsumer.class);

    private final ClaimOrchestrationService orchestrationService;

    public RiskEventConsumer(ClaimOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @RabbitListener(queues = "${claims.broker.risk-scored-queue:q.orchestrator.risk-scored}")
    public void onRiskScored(EventEnvelope<RiskScoredPayload> envelope) {
        log.info("Received RiskScored in orchestrator: eventId={} claimId={}",
                envelope.eventId(),
                envelope.payload() != null ? envelope.payload().claimId() : null);
        orchestrationService.handleRiskScored(envelope);
    }
}