package com.claims_engine.orchestrator.api;

import com.claims_engine.orchestrator.domain.ClaimAuditLogDocument;
import com.claims_engine.orchestrator.domain.ClaimLifecycleStatus;
import java.time.Instant;
import java.util.UUID;

public record AuditEntryResponse(
        String auditId,
        String claimId,
        UUID eventId,
        String eventType,
        ClaimLifecycleStatus previousStatus,
        ClaimLifecycleStatus newStatus,
        String correlationId,
        String causationId,
        String notes,
        Instant occurredAt
) {
    public static AuditEntryResponse from(ClaimAuditLogDocument doc) {
        return new AuditEntryResponse(
                doc.getAuditId(),
                doc.getClaimId(),
                doc.getEventId(),
                doc.getEventType(),
                doc.getPreviousStatus(),
                doc.getNewStatus(),
                doc.getCorrelationId(),
                doc.getCausationId(),
                doc.getNotes(),
                doc.getOccurredAt()
        );
    }
}