package com.claims_engine.ingestion.domain;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ClaimRepository extends MongoRepository<ClaimDocument, String> {
    Optional<ClaimDocument> findByIdempotencyKey(String idempotencyKey);
}