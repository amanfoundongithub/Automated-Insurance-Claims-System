package com.claims_engine.settlement.domain;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "payout_audit_logs")
public class PayoutAuditLogDocument {

    @Id
    private String auditId;

    @Indexed
    private String payoutId;

    @Indexed
    private String claimId;

    private UUID eventId;

    private String eventType;

    private PayoutStatus previousStatus;

    private PayoutStatus newStatus;

    private String correlationId;

    private String causationId;

    private String notes;

    private Instant occurredAt;
}