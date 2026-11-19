package com.claims_engine.ingestion.outbox;

import com.claims_engine.ingestion.messaging.ClaimSubmittedPayload;
import com.claims_engine.ingestion.messaging.EventEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "outbox_events")
@CompoundIndex(name = "idx_outbox_status_created", def = "{'status': 1, 'createdAt': 1}")
public class OutboxEventDocument {

    @Id
    private UUID eventId;

    @Indexed
    private String aggregateId; // claimId

    private String eventType;
    private String exchange;
    private String routingKey;
    private EventEnvelope<ClaimSubmittedPayload> envelope;

    private OutboxStatus status = OutboxStatus.PENDING;
    private int retryCount = 0;
    private String lastError;
    private Instant publishedAt;

    @CreatedDate
    private Instant createdAt;

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }

    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }

    public String getRoutingKey() { return routingKey; }
    public void setRoutingKey(String routingKey) { this.routingKey = routingKey; }

    public EventEnvelope<ClaimSubmittedPayload> getEnvelope() { return envelope; }
    public void setEnvelope(EventEnvelope<ClaimSubmittedPayload> envelope) { this.envelope = envelope; }

    public OutboxStatus getStatus() { return status; }
    public void setStatus(OutboxStatus status) { this.status = status; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}