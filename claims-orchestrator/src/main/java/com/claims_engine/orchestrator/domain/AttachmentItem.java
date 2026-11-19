package com.claims_engine.orchestrator.domain;

public record AttachmentItem(
        String documentId,
        String docType,
        String uri
) {}