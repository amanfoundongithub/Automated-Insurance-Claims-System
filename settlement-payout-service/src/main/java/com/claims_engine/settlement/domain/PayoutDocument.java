package com.claims_engine.settlement.domain;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "payouts")
public class PayoutDocument {

    @Id
    private String payoutId;

    @Indexed(unique = true)
    private String claimId;

    private String policyId;

    private String policyholderId;

    @Indexed(unique = true)
    private String settlementReference;

    private BigDecimal amount;

    private String currency;

    private String riskTier;

    private PayoutStatus status;

    private String providerReference;

    private String failureReason;

    private boolean retryable;

    private int attemptCount;

    private String lastCorrelationId;

    private String lastCausationId;

    private Instant settledAt;

    private Instant failedAt;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}