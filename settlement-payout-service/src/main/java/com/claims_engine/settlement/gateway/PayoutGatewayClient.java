package com.claims_engine.settlement.gateway;

import com.claims_engine.settlement.domain.PayoutDocument;

public interface PayoutGatewayClient {
    GatewayOutcome executePayout(PayoutDocument payout);
}