package com.claims_engine.policy.messaging;

import com.claims_engine.policy.domain.Money;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ClaimSubmittedPayload(
        String claimId,
        String policyId,
        String policyholderId,
        String claimType,
        Instant incidentOccurredAt,
        Instant reportedAt,
        Money claimedAmount,
        Map<String, Object> incidentDetails,
        List<AttachmentItemPayload> attachments
) {
    public record AttachmentItemPayload(
            String documentId,
            String docType,
            String uri
    ) {}
}