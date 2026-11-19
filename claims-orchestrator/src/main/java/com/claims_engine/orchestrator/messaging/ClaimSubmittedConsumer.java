package com.claims_engine.orchestrator.messaging;

import com.claims_engine.orchestrator.service.ClaimOrchestrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class ClaimSubmittedConsumer {

    private static final Logger log = LoggerFactory.getLogger(ClaimSubmittedConsumer.class);

    private final ClaimOrchestrationService orchestrationService;

    public ClaimSubmittedConsumer(ClaimOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @RabbitListener(queues = "${claims.broker.claim-submitted-queue}")
    public void onClaimSubmitted(
            EventEnvelope<ClaimSubmittedPayload> envelope,
            @Header(value = "X-Correlation-Id", required = false) String correlationIdHeader
    ) {
        log.info("Received message on claim-submitted queue: eventId={} claimId={} headerCorrelationId={}",
                envelope.eventId(),
                envelope.payload() != null ? envelope.payload().claimId() : null,
                correlationIdHeader);
        orchestrationService.handleClaimSubmitted(envelope);
    }
}