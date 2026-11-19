package com.claims_engine.ingestion.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "claims")
@CompoundIndex(name = "idx_claims_policy_incident", def = "{'policyId': 1, 'incidentOccurredAt': -1}")
public class ClaimDocument {

    @Id
    private String claimId;

    @Indexed(unique = true)
    private String idempotencyKey;

    @Indexed
    private String policyId;

    private String policyholderId;
    private String claimType;
    private Instant incidentOccurredAt;
    private Instant reportedAt;
    private Money claimedAmount;
    private Map<String, Object> incidentDetails;
    private List<AttachmentItem> attachments;
    private ClaimStatus status;
    private String correlationId;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    // Getters and setters (or standard accessors)
    public String getClaimId() { return claimId; }
    public void setClaimId(String claimId) { this.claimId = claimId; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getPolicyId() { return policyId; }
    public void setPolicyId(String policyId) { this.policyId = policyId; }

    public String getPolicyholderId() { return policyholderId; }
    public void setPolicyholderId(String policyholderId) { this.policyholderId = policyholderId; }

    public String getClaimType() { return claimType; }
    public void setClaimType(String claimType) { this.claimType = claimType; }

    public Instant getIncidentOccurredAt() { return incidentOccurredAt; }
    public void setIncidentOccurredAt(Instant incidentOccurredAt) { this.incidentOccurredAt = incidentOccurredAt; }

    public Instant getReportedAt() { return reportedAt; }
    public void setReportedAt(Instant reportedAt) { this.reportedAt = reportedAt; }

    public Money getClaimedAmount() { return claimedAmount; }
    public void setClaimedAmount(Money claimedAmount) { this.claimedAmount = claimedAmount; }

    public Map<String, Object> getIncidentDetails() { return incidentDetails; }
    public void setIncidentDetails(Map<String, Object> incidentDetails) { this.incidentDetails = incidentDetails; }

    public List<AttachmentItem> getAttachments() { return attachments; }
    public void setAttachments(List<AttachmentItem> attachments) { this.attachments = attachments; }

    public ClaimStatus getStatus() { return status; }
    public void setStatus(ClaimStatus status) { this.status = status; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}