package com.claims_engine.settlement.api;

public class PayoutNotFoundException extends RuntimeException {

    public PayoutNotFoundException(String message) {
        super(message);
    }
}