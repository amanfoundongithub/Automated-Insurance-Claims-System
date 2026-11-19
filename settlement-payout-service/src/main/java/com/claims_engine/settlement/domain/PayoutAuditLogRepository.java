package com.claims_engine.settlement.domain;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PayoutAuditLogRepository extends MongoRepository<PayoutAuditLogDocument, String> {

    List<PayoutAuditLogDocument> findByClaimIdOrderByOccurredAtAsc(String claimId);

    List<PayoutAuditLogDocument> findByPayoutIdOrderByOccurredAtAsc(String payoutId);
}