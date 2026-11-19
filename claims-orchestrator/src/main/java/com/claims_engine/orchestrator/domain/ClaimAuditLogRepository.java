package com.claims_engine.orchestrator.domain;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ClaimAuditLogRepository extends MongoRepository<ClaimAuditLogDocument, String> {

    List<ClaimAuditLogDocument> findByClaimIdOrderByOccurredAtAsc(String claimId);
}