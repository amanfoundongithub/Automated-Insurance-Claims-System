package com.claims_engine.orchestrator.api;

import com.claims_engine.orchestrator.domain.ClaimAuditLogRepository;
import com.claims_engine.orchestrator.domain.ClaimLifecycleDocument;
import com.claims_engine.orchestrator.domain.ClaimLifecycleRepository;
import com.claims_engine.orchestrator.service.ClaimOrchestrationService;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/claims")
public class ClaimStateController {

    private final ClaimLifecycleRepository lifecycleRepository;
    private final ClaimAuditLogRepository auditLogRepository;
    private final ClaimOrchestrationService orchestrationService;

    public ClaimStateController(
            ClaimLifecycleRepository lifecycleRepository,
            ClaimAuditLogRepository auditLogRepository,
            ClaimOrchestrationService orchestrationService
    ) {
        this.lifecycleRepository = lifecycleRepository;
        this.auditLogRepository = auditLogRepository;
        this.orchestrationService = orchestrationService;
    }

    public record ReviewDecisionRequest(String reason) {}

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

    @PostMapping("/{claimId}/approve")
    public ResponseEntity<?> approveClaim(
            @PathVariable String claimId,
            @RequestBody(required = false) ReviewDecisionRequest request
    ) {
        try {
            ClaimLifecycleDocument updated = orchestrationService.approveClaim(
                    claimId,
                    request != null ? request.reason() : null
            );
            return ResponseEntity.ok(ClaimStateResponse.from(updated));
        } catch (IllegalArgumentException ex) {
            throw new NoSuchElementException(ex.getMessage());
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/{claimId}/reject")
    public ResponseEntity<?> rejectClaim(
            @PathVariable String claimId,
            @RequestBody(required = false) ReviewDecisionRequest request
    ) {
        try {
            ClaimLifecycleDocument updated = orchestrationService.rejectClaim(
                    claimId,
                    request != null ? request.reason() : null
            );
            return ResponseEntity.ok(ClaimStateResponse.from(updated));
        } catch (IllegalArgumentException ex) {
            throw new NoSuchElementException(ex.getMessage());
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
        }
    }
}