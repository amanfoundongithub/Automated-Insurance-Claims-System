package com.claims_engine.orchestrator.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ClaimLifecycleRepository extends MongoRepository<ClaimLifecycleDocument, String> {
}