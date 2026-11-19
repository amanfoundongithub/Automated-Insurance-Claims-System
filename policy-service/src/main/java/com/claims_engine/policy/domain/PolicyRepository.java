package com.claims_engine.policy.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface PolicyRepository extends MongoRepository<PolicyDocument, String> {
}