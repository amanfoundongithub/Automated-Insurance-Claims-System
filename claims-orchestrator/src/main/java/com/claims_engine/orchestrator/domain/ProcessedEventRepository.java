package com.claims_engine.orchestrator.domain;

import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProcessedEventRepository extends MongoRepository<ProcessedEventDocument, UUID> {
}