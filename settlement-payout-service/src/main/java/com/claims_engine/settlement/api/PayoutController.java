package com.claims_engine.settlement.api;

import com.claims_engine.settlement.domain.PayoutAuditLogRepository;
import com.claims_engine.settlement.domain.PayoutDocument;
import com.claims_engine.settlement.domain.PayoutRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payouts")
@RequiredArgsConstructor
public class PayoutController {

    private final PayoutRepository payoutRepository;
    private final PayoutAuditLogRepository auditLogRepository;

    @GetMapping("/claims/{claimId}")
    public ResponseEntity<PayoutStatusResponse> getByClaimId(@PathVariable String claimId) {
        PayoutDocument payout = payoutRepository.findByClaimId(claimId)
                .orElseThrow(() -> new PayoutNotFoundException("Payout not found for claimId: " + claimId));
        return ResponseEntity.ok(PayoutStatusResponse.from(payout));
    }

    @GetMapping("/{payoutId}")
    public ResponseEntity<PayoutStatusResponse> getByPayoutId(@PathVariable String payoutId) {
        PayoutDocument payout = payoutRepository.findById(payoutId)
                .orElseThrow(() -> new PayoutNotFoundException("Payout not found for payoutId: " + payoutId));
        return ResponseEntity.ok(PayoutStatusResponse.from(payout));
    }

    @GetMapping("/claims/{claimId}/audit")
    public ResponseEntity<List<PayoutAuditEntryResponse>> getAuditTrail(@PathVariable String claimId) {
        List<PayoutAuditEntryResponse> entries = auditLogRepository
                .findByClaimIdOrderByOccurredAtAsc(claimId)
                .stream()
                .map(PayoutAuditEntryResponse::from)
                .toList();
        return ResponseEntity.ok(entries);
    }
}