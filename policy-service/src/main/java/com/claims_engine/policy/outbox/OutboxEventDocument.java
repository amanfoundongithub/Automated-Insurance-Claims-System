package com.claims_engine.policy.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "policy_outbox_events")
@CompoundIndex(name = "idx_policy_outbox_status_created", def = "{'status': 1, 'createdAt': 1}")
public class OutboxEventDocument {

    @Id
    private UUID eventId;

    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String eventVersion;
    private String routingKey;
    private String correlationId;
    private String causationId;
    private Map<String, Object> payload;
    private OutboxStatus status;
    private int retryCount;
    private String lastError;
    private Instant publishedAt;

    @CreatedDate
    private Instant createdAt;

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }

    public String getAggregateType() { return aggregateType; }
    public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }

    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getEventVersion() { return eventVersion; }
    public void setEventVersion(String eventVersion) { this.eventVersion = eventVersion; }

    public String getRoutingKey() { return routingKey; }
    public void setRoutingKey(String routingKey) { this.routingKey = routingKey; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getCausationId() { return causationId; }
    public void setCausationId(String causationId) { this.causationId = causationId; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }

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