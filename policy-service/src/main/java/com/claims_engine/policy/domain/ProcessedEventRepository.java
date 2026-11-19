package com.claims_engine.policy.domain;

import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProcessedEventRepository extends MongoRepository<ProcessedEventDocument, UUID> {
}