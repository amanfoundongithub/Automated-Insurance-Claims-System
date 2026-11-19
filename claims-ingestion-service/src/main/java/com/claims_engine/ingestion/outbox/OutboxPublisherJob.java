package com.claims_engine.ingestion.outbox;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class OutboxPublisherJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherJob.class);

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final int batchSize;
    private final int maxRetries;

    public OutboxPublisherJob(
            OutboxRepository outboxRepository,
            RabbitTemplate rabbitTemplate,
            @Value("${claims.outbox.batch-size:50}") int batchSize,
            @Value("${claims.outbox.max-retries:5}") int maxRetries
    ) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
    }

    @Scheduled(fixedDelayString = "${claims.outbox.poll-interval-ms:1000}")
    public void publishPendingEvents() {
        List<OutboxEventDocument> pendingEvents = outboxRepository.findByStatusOrderByCreatedAtAsc(
                OutboxStatus.PENDING,
                PageRequest.of(0, batchSize)
        );

        for (OutboxEventDocument outboxEvent : pendingEvents) {
            try {
                var envelope = outboxEvent.getEnvelope();
                rabbitTemplate.convertAndSend(
                        outboxEvent.getExchange(),
                        outboxEvent.getRoutingKey(),
                        envelope,
                        message -> {
                            var props = message.getMessageProperties();
                            props.setMessageId(outboxEvent.getEventId().toString());
                            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                            props.setHeader("X-Correlation-Id", envelope.correlationId());
                            props.setHeader("X-Causation-Id", envelope.causationId());
                            props.setHeader("X-Partition-Key", envelope.partitionKey());
                            props.setHeader("X-Event-Type", envelope.eventType());
                            props.setHeader("X-Event-Version", envelope.eventVersion());
                            return message;
                        }
                );

                outboxEvent.setStatus(OutboxStatus.PUBLISHED);
                outboxEvent.setPublishedAt(Instant.now());
                outboxEvent.setLastError(null);
                outboxRepository.save(outboxEvent);

                log.info("Published outbox eventId={} claimId={} routingKey={}",
                        outboxEvent.getEventId(), outboxEvent.getAggregateId(), outboxEvent.getRoutingKey());
            } catch (Exception ex) {
                int nextRetries = outboxEvent.getRetryCount() + 1;
                outboxEvent.setRetryCount(nextRetries);
                outboxEvent.setLastError(truncateError(ex.getMessage()));

                if (nextRetries >= maxRetries) {
                    outboxEvent.setStatus(OutboxStatus.FAILED);
                    log.error("Outbox event marked FAILED after {} retries: eventId={} claimId={}",
                            nextRetries, outboxEvent.getEventId(), outboxEvent.getAggregateId(), ex);
                } else {
                    log.warn("Failed to publish outbox event (attempt {}/{}): eventId={} claimId={}",
                            nextRetries, maxRetries, outboxEvent.getEventId(), outboxEvent.getAggregateId(), ex);
                }
                outboxRepository.save(outboxEvent);
            }
        }
    }

    private String truncateError(String msg) {
        if (msg == null) return "Unknown error";
        return msg.length() <= 500 ? msg : msg.substring(0, 500);
    }
}