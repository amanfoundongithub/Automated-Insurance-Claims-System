package com.claims_engine.policy.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "policy_processed_events")
public class ProcessedEventDocument {

    @Id
    private UUID eventId;

    private String claimId;
    private String policyId;

    @CreatedDate
    private Instant processedAt;

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }

    public String getClaimId() { return claimId; }
    public void setClaimId(String claimId) { this.claimId = claimId; }

    public String getPolicyId() { return policyId; }
    public void setPolicyId(String policyId) { this.policyId = policyId; }

    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}