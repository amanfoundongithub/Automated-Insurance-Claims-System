package com.claims_engine.ingestion.api;

import com.claims_engine.ingestion.service.ClaimIngestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/claims")
public class ClaimController {

    private final ClaimIngestionService claimIngestionService;

    public ClaimController(ClaimIngestionService claimIngestionService) {
        this.claimIngestionService = claimIngestionService;
    }

    @PostMapping
    public ResponseEntity<ClaimResponse> submitClaim(
            @RequestHeader("Idempotency-Key") @NotBlank(message = "Idempotency-Key header is required") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationIdHeader,
            @Valid @RequestBody SubmitClaimRequest request
    ) {
        String correlationId = (correlationIdHeader != null && !correlationIdHeader.isBlank())
                ? correlationIdHeader
                : "corr_" + UUID.randomUUID();

        ClaimResponse response = claimIngestionService.submitClaim(request, idempotencyKey, correlationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("X-Correlation-Id", response.correlationId())
                .body(response);
    }
}