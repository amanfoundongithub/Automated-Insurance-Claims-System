package com.claims_engine.ingestion.api;

import com.claims_engine.ingestion.service.ClaimIngestionService;
import com.claims_engine.ingestion.utils.CorrelationIdGenerator;
import com.claims_engine.ingestion.utils.StringUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final Logger LOGGER = LoggerFactory.getLogger(ClaimController.class);

    public ClaimController(ClaimIngestionService claimIngestionService) {
        this.claimIngestionService = claimIngestionService;
    }

    @PostMapping
    public ResponseEntity<ClaimResponse> submitClaim(
            @RequestHeader("Idempotency-Key") @NotBlank(message = "Idempotency-Key header is required for idempotency.") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationIdHeader,
            @Valid @RequestBody SubmitClaimRequest request
    ) {

        String correlationId = StringUtils.isStringBlank(correlationIdHeader) == false ? correlationIdHeader : CorrelationIdGenerator.generateCorrelationId();
        LOGGER.info(
            "Received request to submit claim for policyId: {}. Assigned correlationID: {} for this settlement.", 
            request.policyId(),
            correlationId
        );

        ClaimResponse response = claimIngestionService.submitClaim(request, idempotencyKey, correlationId);
        LOGGER.info(
            "Claim has been successfully ingested by system. Assigned eventID : {} for the event.", 
            response.eventId()
        );

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .header("X-Correlation-Id", response.correlationId())
                .body(response);

    }
}