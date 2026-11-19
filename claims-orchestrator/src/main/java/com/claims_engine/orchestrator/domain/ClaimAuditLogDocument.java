package com.claims_engine.orchestrator.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "claim_audit_logs")
@CompoundIndex(name = "idx_audit_claim_occurred", def = "{'claimId': 1, 'occurredAt': 1}")
public class ClaimAuditLogDocument {

    @Id
    private String auditId;

    @Indexed
    private String claimId;

    private UUID eventId;
    private String eventType;
    private ClaimLifecycleStatus previousStatus;
    private ClaimLifecycleStatus newStatus;
    private String correlationId;
    private String causationId;
    private String notes;
    private Instant occurredAt;

    @CreatedDate
    private Instant createdAt;

    public String getAuditId() { return auditId; }
    public void setAuditId(String auditId) { this.auditId = auditId; }

    public String getClaimId() { return claimId; }
    public void setClaimId(String claimId) { this.claimId = claimId; }

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public ClaimLifecycleStatus getPreviousStatus() { return previousStatus; }
    public void setPreviousStatus(ClaimLifecycleStatus previousStatus) { this.previousStatus = previousStatus; }

    public ClaimLifecycleStatus getNewStatus() { return newStatus; }
    public void setNewStatus(ClaimLifecycleStatus newStatus) { this.newStatus = newStatus; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getCausationId() { return causationId; }
    public void setCausationId(String causationId) { this.causationId = causationId; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}