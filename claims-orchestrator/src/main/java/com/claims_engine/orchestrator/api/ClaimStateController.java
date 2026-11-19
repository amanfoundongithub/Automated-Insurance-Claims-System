package com.claims_engine.orchestrator.api;

import com.claims_engine.orchestrator.domain.ClaimAuditLogRepository;
import com.claims_engine.orchestrator.domain.ClaimLifecycleRepository;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/claims")
public class ClaimStateController {

    private final ClaimLifecycleRepository lifecycleRepository;
    private final ClaimAuditLogRepository auditLogRepository;

    public ClaimStateController(
            ClaimLifecycleRepository lifecycleRepository,
            ClaimAuditLogRepository auditLogRepository
    ) {
        this.lifecycleRepository = lifecycleRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping("/{claimId}")
    public ResponseEntity<ClaimStateResponse> getClaimState(@PathVariable String claimId) {
        return lifecycleRepository.findById(claimId)
                .map(doc -> ResponseEntity.ok(ClaimStateResponse.from(doc)))
                .orElseThrow(() -> new NoSuchElementException("Claim not found: " + claimId));
    }

    @GetMapping("/{claimId}/audit")
    public ResponseEntity<List<AuditEntryResponse>> getClaimAudit(@PathVariable String claimId) {
        if (!lifecycleRepository.existsById(claimId)) {
            throw new NoSuchElementException("Claim not found: " + claimId);
        }
        List<AuditEntryResponse> entries = auditLogRepository.findByClaimIdOrderByOccurredAtAsc(claimId)
                .stream()
                .map(AuditEntryResponse::from)
                .toList();
        return ResponseEntity.ok(entries);
    }
}