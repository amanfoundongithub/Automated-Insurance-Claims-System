package com.claims_engine.policy.api;

import com.claims_engine.policy.domain.PolicyDocument;
import com.claims_engine.policy.domain.PolicyRepository;
import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/policies")
public class PolicyController {

    private final PolicyRepository policyRepository;

    public PolicyController(PolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    @PostMapping
    public ResponseEntity<PolicyResponse> createOrUpdatePolicy(@Valid @RequestBody CreatePolicyRequest request) {
        PolicyDocument doc = policyRepository.findById(request.policyId()).orElseGet(PolicyDocument::new);
        doc.setPolicyId(request.policyId());
        doc.setPolicyholderId(request.policyholderId());
        doc.setStatus(request.status());
        doc.setEffectiveFrom(request.effectiveFrom());
        doc.setEffectiveTo(request.effectiveTo());
        doc.setEligibleClaimTypes(request.eligibleClaimTypes());
        doc.setMaxCoverageAmount(request.maxCoverageAmount());
        doc.setDeductibleAmount(request.deductibleAmount());
        doc.setCoPayRatio(request.coPayRatio());

        PolicyDocument saved = policyRepository.save(doc);
        return ResponseEntity.status(HttpStatus.CREATED).body(PolicyResponse.from(saved));
    }

    @GetMapping("/{policyId}")
    public ResponseEntity<PolicyResponse> getPolicy(@PathVariable String policyId) {
        return policyRepository.findById(policyId)
                .map(doc -> ResponseEntity.ok(PolicyResponse.from(doc)))
                .orElseThrow(() -> new NoSuchElementException("Policy not found: " + policyId));
    }
}