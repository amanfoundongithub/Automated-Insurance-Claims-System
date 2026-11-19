package com.claims_engine.orchestrator.api;

import com.claims_engine.orchestrator.domain.ClaimLifecycleDocument;
import com.claims_engine.orchestrator.domain.ClaimLifecycleStatus;
import com.claims_engine.orchestrator.domain.Money;
import java.time.Instant;

public record ClaimStateResponse(
        String claimId,
        String policyId,
        String policyholderId,
        String claimType,
        Instant incidentOccurredAt,
        Instant reportedAt,
        Money claimedAmount,
        ClaimLifecycleStatus status,
        String correlationId,
        String lastEventType,
        Instant createdAt,
        Instant updatedAt
) {
    public static ClaimStateResponse from(ClaimLifecycleDocument doc) {
        return new ClaimStateResponse(
                doc.getClaimId(),
                doc.getPolicyId(),
                doc.getPolicyholderId(),
                doc.getClaimType(),
                doc.getIncidentOccurredAt(),
                doc.getReportedAt(),
                doc.getClaimedAmount(),
                doc.getStatus(),
                doc.getCorrelationId(),
                doc.getLastEventType(),
                doc.getCreatedAt(),
                doc.getUpdatedAt()
        );
    }
}