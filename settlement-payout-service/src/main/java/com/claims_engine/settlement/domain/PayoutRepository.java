package com.claims_engine.settlement.domain;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PayoutRepository extends MongoRepository<PayoutDocument, String> {

    Optional<PayoutDocument> findByClaimId(String claimId);

    Optional<PayoutDocument> findBySettlementReference(String settlementReference);

    boolean existsByClaimId(String claimId);
}