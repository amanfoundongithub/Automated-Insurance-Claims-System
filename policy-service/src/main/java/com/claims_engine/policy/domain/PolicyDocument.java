package com.claims_engine.policy.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "policies")
public class PolicyDocument {

    @Id
    private String policyId;

    @Indexed
    private String policyholderId;

    private PolicyStatus status;
    private Instant effectiveFrom;
    private Instant effectiveTo;
    private Set<String> eligibleClaimTypes;
    private Money maxCoverageAmount;
    private Money deductibleAmount;
    private BigDecimal coPayRatio;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public String getPolicyId() { return policyId; }
    public void setPolicyId(String policyId) { this.policyId = policyId; }

    public String getPolicyholderId() { return policyholderId; }
    public void setPolicyholderId(String policyholderId) { this.policyholderId = policyholderId; }

    public PolicyStatus getStatus() { return status; }
    public void setStatus(PolicyStatus status) { this.status = status; }

    public Instant getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(Instant effectiveFrom) { this.effectiveFrom = effectiveFrom; }

    public Instant getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(Instant effectiveTo) { this.effectiveTo = effectiveTo; }

    public Set<String> getEligibleClaimTypes() { return eligibleClaimTypes; }
    public void setEligibleClaimTypes(Set<String> eligibleClaimTypes) { this.eligibleClaimTypes = eligibleClaimTypes; }

    public Money getMaxCoverageAmount() { return maxCoverageAmount; }
    public void setMaxCoverageAmount(Money maxCoverageAmount) { this.maxCoverageAmount = maxCoverageAmount; }

    public Money getDeductibleAmount() { return deductibleAmount; }
    public void setDeductibleAmount(Money deductibleAmount) { this.deductibleAmount = deductibleAmount; }

    public BigDecimal getCoPayRatio() { return coPayRatio; }
    public void setCoPayRatio(BigDecimal coPayRatio) { this.coPayRatio = coPayRatio; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}