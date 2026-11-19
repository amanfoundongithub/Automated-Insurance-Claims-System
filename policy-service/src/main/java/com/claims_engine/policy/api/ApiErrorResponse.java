package com.claims_engine.policy.api;

import java.time.Instant;

public record ApiErrorResponse(
        String error,
        String message,
        Instant timestamp
) {}