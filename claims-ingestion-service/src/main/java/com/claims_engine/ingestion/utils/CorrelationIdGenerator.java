package com.claims_engine.ingestion.utils;

import java.util.UUID;

public class CorrelationIdGenerator {

    public static String generateCorrelationId() {
        return "corr_" + UUID.randomUUID().toString();
    }

}
