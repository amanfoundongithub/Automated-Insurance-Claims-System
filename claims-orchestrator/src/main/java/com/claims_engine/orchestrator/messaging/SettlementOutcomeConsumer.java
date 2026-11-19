package com.claims_engine.orchestrator.messaging;

import com.claims_engine.orchestrator.service.ClaimOrchestrationService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
public class SettlementOutcomeConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementOutcomeConsumer.class);

    private final ClaimOrchestrationService orchestrationService;

    public SettlementOutcomeConsumer(ClaimOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @RabbitListener(queues = "${claims.settlement-completed-queue:q.orchestrator.settlement-completed}")
    public void onSettlementCompleted(@Valid EventEnvelope<SettlementCompletedPayload> envelope) {
        log.info(
                "Received SettlementCompleted eventId={} claimId={} correlationId={} causationId={}",
                envelope.eventId(),
                envelope.payload().claimId(),
                envelope.correlationId(),
                envelope.causationId()
        );
        orchestrationService.handleSettlementCompleted(envelope);
    }

    @RabbitListener(queues = "${claims.settlement-failed-queue:q.orchestrator.settlement-failed}")
    public void onSettlementFailed(@Valid EventEnvelope<SettlementFailedPayload> envelope) {
        log.warn(
                "Received SettlementFailed eventId={} claimId={} correlationId={} causationId={}",
                envelope.eventId(),
                envelope.payload().claimId(),
                envelope.correlationId(),
                envelope.causationId()
        );
        orchestrationService.handleSettlementFailed(envelope);
    }
}