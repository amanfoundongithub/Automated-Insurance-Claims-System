package com.claims_engine.policy.outbox;

import com.claims_engine.policy.messaging.EventEnvelope;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisherJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherJob.class);

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final String exchangeName;
    private final int batchSize;
    private final int maxRetries;

    public OutboxPublisherJob(
            OutboxRepository outboxRepository,
            RabbitTemplate rabbitTemplate,
            @Value("${claims.broker.exchange}") String exchangeName,
            @Value("${claims.outbox.batch-size:50}") int batchSize,
            @Value("${claims.outbox.max-retries:5}") int maxRetries
    ) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.exchangeName = exchangeName;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
    }

    @Scheduled(fixedDelayString = "${claims.outbox.poll-interval-ms:1000}")
    public void publishPendingEvents() {
        List<OutboxEventDocument> batch = outboxRepository.findByStatusOrderByCreatedAtAsc(
                OutboxStatus.PENDING,
                PageRequest.of(0, batchSize)
        );

        for (OutboxEventDocument event : batch) {
            try {
                EventEnvelope<?> envelope = new EventEnvelope<>(
                        event.getEventId(),
                        event.getEventType(),
                        event.getEventVersion(),
                        Instant.now(),
                        "policy-service",
                        event.getCorrelationId(),
                        event.getCausationId(),
                        event.getAggregateId(),
                        event.getPayload()
                );

                rabbitTemplate.convertAndSend(exchangeName, event.getRoutingKey(), envelope, message -> {
                    if (event.getCorrelationId() != null) {
                        message.getMessageProperties().setHeader("X-Correlation-Id", event.getCorrelationId());
                    }
                    message.getMessageProperties().setMessageId(event.getEventId().toString());
                    return message;
                });

                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(Instant.now());
                event.setLastError(null);
                outboxRepository.save(event);

                log.info("Published policy outbox event: eventId={} type={} routingKey={} claimId={}",
                        event.getEventId(), event.getEventType(), event.getRoutingKey(), event.getAggregateId());
            } catch (AmqpConnectException | AmqpIOException brokerDown) {
                int nextRetry = event.getRetryCount() + 1;
                event.setRetryCount(nextRetry);
                event.setLastError("Broker unavailable: " + brokerDown.getMessage());
                outboxRepository.save(event);
                log.warn("RabbitMQ unavailable during policy outbox publish (retry={}): eventId={}",
                        nextRetry, event.getEventId());
            } catch (Exception ex) {
                int nextRetry = event.getRetryCount() + 1;
                event.setRetryCount(nextRetry);
                event.setLastError(ex.getMessage());
                if (nextRetry >= maxRetries) {
                    event.setStatus(OutboxStatus.FAILED);
                    log.error("Policy outbox event marked FAILED after {} retries: eventId={} claimId={}",
                            maxRetries, event.getEventId(), event.getAggregateId(), ex);
                } else {
                    log.warn("Policy outbox publish attempt {} failed: eventId={} claimId={}",
                            nextRetry, event.getEventId(), event.getAggregateId(), ex);
                }
                outboxRepository.save(event);
            }
        }
    }
}