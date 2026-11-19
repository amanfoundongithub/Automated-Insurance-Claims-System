package com.claims_engine.settlement.messaging;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Value("${settlement.broker.exchange:claims.events.topic}")
    private String exchangeName;

    @Value("${settlement.broker.settlement-completed-routing-key:settlement.completed}")
    private String settlementCompletedRoutingKey;

    @Value("${settlement.broker.settlement-failed-routing-key:settlement.failed}")
    private String settlementFailedRoutingKey;

    @Value("${spring.application.name:settlement-payout-service}")
    private String producerName;

    public void publishSettlementCompleted(
            SettlementCompletedPayload payload,
            String correlationId,
            String causationId
    ) {
        EventEnvelope<SettlementCompletedPayload> envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                "SettlementCompleted",
                "1.0",
                Instant.now(),
                producerName,
                correlationId,
                causationId,
                payload.claimId(),
                payload
        );
        log.info(
                "Publishing SettlementCompleted eventId={} claimId={} payoutId={} correlationId={} causationId={}",
                envelope.eventId(),
                payload.claimId(),
                payload.payoutId(),
                correlationId,
                causationId
        );
        rabbitTemplate.convertAndSend(exchangeName, settlementCompletedRoutingKey, envelope);
    }

    public void publishSettlementFailed(
            SettlementFailedPayload payload,
            String correlationId,
            String causationId
    ) {
        EventEnvelope<SettlementFailedPayload> envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                "SettlementFailed",
                "1.0",
                Instant.now(),
                producerName,
                correlationId,
                causationId,
                payload.claimId(),
                payload
        );
        log.warn(
                "Publishing SettlementFailed eventId={} claimId={} payoutId={} retryable={} correlationId={} causationId={}",
                envelope.eventId(),
                payload.claimId(),
                payload.payoutId(),
                payload.retryable(),
                correlationId,
                causationId
        );
        rabbitTemplate.convertAndSend(exchangeName, settlementFailedRoutingKey, envelope);
    }
}