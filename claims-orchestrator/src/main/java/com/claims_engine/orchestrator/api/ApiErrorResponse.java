package com.claims_engine.orchestrator.api;

import java.time.Instant;

public record ApiErrorResponse(
        String error,
        String message,
        Instant timestamp
) {}