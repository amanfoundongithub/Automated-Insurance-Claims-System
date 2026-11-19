package com.claims_engine.orchestrator.messaging;

import com.claims_engine.orchestrator.service.ClaimOrchestrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ValuationEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ValuationEventConsumer.class);

    private final ClaimOrchestrationService orchestrationService;

    public ValuationEventConsumer(ClaimOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @RabbitListener(queues = "${claims.broker.valuation-completed-queue:q.orchestrator.valuation-completed}")
    public void onValuationCompleted(EventEnvelope<ValuationCompletedPayload> envelope) {
        log.info("Received ValuationCompleted in orchestrator: eventId={} claimId={}",
                envelope.eventId(),
                envelope.payload() != null ? envelope.payload().claimId() : null);
        orchestrationService.handleValuationCompleted(envelope);
    }
}