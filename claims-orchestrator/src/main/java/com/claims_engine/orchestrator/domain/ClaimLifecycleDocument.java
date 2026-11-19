package com.claims_engine.orchestrator.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "claim_lifecycle")
@CompoundIndex(name = "idx_lifecycle_policy_status", def = "{'policyId': 1, 'status': 1}")
public class ClaimLifecycleDocument {

    @Id
    private String claimId;

    @Indexed
    private String policyId;

    private String policyholderId;
    private String claimType;
    private Instant incidentOccurredAt;
    private Instant reportedAt;
    private Money claimedAmount;
    private Map<String, Object> incidentDetails;
    private List<AttachmentItem> attachments;
    private ClaimLifecycleStatus status;
    private String correlationId;
    private String lastEventType;
    private Money maxCoverageAmount;
    private Money deductibleAmount;
    private BigDecimal coPayRatio;
    private List<String> policyValidationReasons;
    private Integer riskScore;
    private String riskTier;
    private List<String> riskFlags;
    private Boolean requiresManualReview;
    private Money eligibleGrossAmount;
    private Money deductibleAppliedAmount;
    private Money coPayDeductionAmount;
    private Money estimatedNetPayout;
    private Money recommendedReserve;
    private String settlementReference;
    private Instant settledAt;
    private String decisionReason;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public String getClaimId() { return claimId; }
    public void setClaimId(String claimId) { this.claimId = claimId; }

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

    public ClaimLifecycleStatus getStatus() { return status; }
    public void setStatus(ClaimLifecycleStatus status) { this.status = status; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getLastEventType() { return lastEventType; }
    public void setLastEventType(String lastEventType) { this.lastEventType = lastEventType; }

    public Money getMaxCoverageAmount() { return maxCoverageAmount; }
    public void setMaxCoverageAmount(Money maxCoverageAmount) { this.maxCoverageAmount = maxCoverageAmount; }

    public Money getDeductibleAmount() { return deductibleAmount; }
    public void setDeductibleAmount(Money deductibleAmount) { this.deductibleAmount = deductibleAmount; }

    public BigDecimal getCoPayRatio() { return coPayRatio; }
    public void setCoPayRatio(BigDecimal coPayRatio) { this.coPayRatio = coPayRatio; }

    public List<String> getPolicyValidationReasons() { return policyValidationReasons; }
    public void setPolicyValidationReasons(List<String> policyValidationReasons) {
        this.policyValidationReasons = policyValidationReasons;
    }

    public Integer getRiskScore() { return riskScore; }
    public void setRiskScore(Integer riskScore) { this.riskScore = riskScore; }

    public String getRiskTier() { return riskTier; }
    public void setRiskTier(String riskTier) { this.riskTier = riskTier; }

    public List<String> getRiskFlags() { return riskFlags; }
    public void setRiskFlags(List<String> riskFlags) { this.riskFlags = riskFlags; }

    public Boolean getRequiresManualReview() { return requiresManualReview; }
    public void setRequiresManualReview(Boolean requiresManualReview) {
        this.requiresManualReview = requiresManualReview;
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Money getEligibleGrossAmount() { return eligibleGrossAmount; }
    public void setEligibleGrossAmount(Money eligibleGrossAmount) { this.eligibleGrossAmount = eligibleGrossAmount; }

    public Money getDeductibleAppliedAmount() { return deductibleAppliedAmount; }
    public void setDeductibleAppliedAmount(Money deductibleAppliedAmount) { this.deductibleAppliedAmount = deductibleAppliedAmount; }

    public Money getCoPayDeductionAmount() { return coPayDeductionAmount; }
    public void setCoPayDeductionAmount(Money coPayDeductionAmount) { this.coPayDeductionAmount = coPayDeductionAmount; }

    public Money getEstimatedNetPayout() { return estimatedNetPayout; }
    public void setEstimatedNetPayout(Money estimatedNetPayout) { this.estimatedNetPayout = estimatedNetPayout; }

    public Money getRecommendedReserve() { return recommendedReserve; }
    public void setRecommendedReserve(Money recommendedReserve) { this.recommendedReserve = recommendedReserve; }

    public String getSettlementReference() { return settlementReference; }
    public void setSettlementReference(String settlementReference) { this.settlementReference = settlementReference; }

    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }

    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
}