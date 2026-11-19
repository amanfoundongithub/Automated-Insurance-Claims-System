package com.claims_engine.ingestion.domain;

import jakarta.validation.constraints.NotBlank;

public record AttachmentItem(
        @NotBlank String documentId,
        @NotBlank String docType,
        @NotBlank String uri
) {}