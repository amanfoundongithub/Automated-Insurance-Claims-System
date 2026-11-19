package com.claims_engine.settlement.messaging;

import com.claims_engine.settlement.service.SettlementPayoutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Validated
@RequiredArgsConstructor
public class SettlementRequestedConsumer {

    private final SettlementPayoutService settlementPayoutService;

    @RabbitListener(queues = "${settlement.broker.settlement-requested-queue:q.settlement.settlement-requested}")
    public void onSettlementRequested(@Valid EventEnvelope<SettlementRequestedPayload> envelope) {
        log.info(
                "Received eventId={} eventType={} claimId={} correlationId={} causationId={}",
                envelope.eventId(),
                envelope.eventType(),
                envelope.payload().claimId(),
                envelope.correlationId(),
                envelope.causationId()
        );
        settlementPayoutService.handleSettlementRequested(envelope);
    }
}