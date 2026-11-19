package com.claims_engine.ingestion.messaging;

import com.claims_engine.ingestion.domain.AttachmentItem;
import com.claims_engine.ingestion.domain.Money;
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
        List<AttachmentItem> attachments
) {}