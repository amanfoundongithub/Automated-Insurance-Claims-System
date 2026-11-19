package com.claims_engine.orchestrator.messaging;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SettlementRequestedPublisher {

    private static final Logger log = LoggerFactory.getLogger(SettlementRequestedPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final String exchangeName;
    private final String settlementRequestedRoutingKey;
    private final String producerName;

    public SettlementRequestedPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${claims.broker.exchange}") String exchangeName,
            @Value("${claims.settlement-requested-routing-key:settlement.requested}") String settlementRequestedRoutingKey,
            @Value("${spring.application.name:claims-orchestrator}") String producerName
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchangeName = exchangeName;
        this.settlementRequestedRoutingKey = settlementRequestedRoutingKey;
        this.producerName = producerName;
    }

    public void publish(
            SettlementRequestedPayload payload,
            String correlationId,
            String causationId
    ) {
        EventEnvelope<SettlementRequestedPayload> envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                "SettlementRequested",
                "1.0",
                Instant.now(),
                producerName,
                correlationId,
                causationId,
                payload.claimId(),
                payload
        );
        log.info(
                "Publishing SettlementRequested eventId={} claimId={} settlementReference={} correlationId={} causationId={}",
                envelope.eventId(),
                payload.claimId(),
                payload.settlementReference(),
                correlationId,
                causationId
        );
        rabbitTemplate.convertAndSend(exchangeName, settlementRequestedRoutingKey, envelope);
    }
}