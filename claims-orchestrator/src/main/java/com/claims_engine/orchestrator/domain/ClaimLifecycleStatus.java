package com.claims_engine.orchestrator.domain;

public enum ClaimLifecycleStatus {
    SUBMITTED,
    POLICY_VALIDATION_PENDING,
    POLICY_VALIDATED,
    POLICY_REJECTED,
    RISK_SCORING_PENDING,
    VALUATION_PENDING,
    SETTLEMENT_PENDING,
    APPROVED,
    REJECTED
}