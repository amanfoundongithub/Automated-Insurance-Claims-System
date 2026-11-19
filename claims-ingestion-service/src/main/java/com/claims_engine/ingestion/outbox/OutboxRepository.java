package com.claims_engine.ingestion.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OutboxRepository extends MongoRepository<OutboxEventDocument, UUID> {
    List<OutboxEventDocument> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);
}