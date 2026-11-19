package com.claims_engine.ingestion.api;

import com.claims_engine.ingestion.domain.AttachmentItem;
import com.claims_engine.ingestion.domain.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SubmitClaimRequest(
        @NotBlank(message = "policyId is required")
        String policyId,

        @NotBlank(message = "policyholderId is required")
        String policyholderId,

        @NotBlank(message = "claimType is required")
        String claimType,

        @NotNull(message = "incidentOccurredAt is required")
        Instant incidentOccurredAt,

        @NotNull(message = "claimedAmount is required")
        @Valid
        Money claimedAmount,

        Map<String, Object> incidentDetails,

        @Valid
        List<AttachmentItem> attachments
) {}