package com.claims_engine.ingestion.service;

import com.claims_engine.ingestion.api.ClaimResponse;
import com.claims_engine.ingestion.api.SubmitClaimRequest;

public interface ClaimIngestionService {

    public static final String EVENT_TYPE_CLAIM_SUBMITTED = "ClaimSubmitted";
    public static final String EVENT_VERSION_V1 = "1.0";

    ClaimResponse submitClaim(SubmitClaimRequest request, String idempotencyKey, String correlationId);
    
}