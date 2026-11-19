package com.claims_engine.settlement.api;

import com.claims_engine.settlement.domain.PayoutAuditLogDocument;
import com.claims_engine.settlement.domain.PayoutStatus;
import java.time.Instant;
import java.util.UUID;

public record PayoutAuditEntryResponse(
        String auditId,
        String payoutId,
        String claimId,
        UUID eventId,
        String eventType,
        PayoutStatus previousStatus,
        PayoutStatus newStatus,
        String correlationId,
        String causationId,
        String notes,
        Instant occurredAt
) {
    public static PayoutAuditEntryResponse from(PayoutAuditLogDocument doc) {
        return new PayoutAuditEntryResponse(
                doc.getAuditId(),
                doc.getPayoutId(),
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