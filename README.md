# Event-Driven Insurance Claims Processing & Settlement Engine

An enterprise-grade, distributed claims processing platform designed to orchestrate the end-to-end lifecycle of insurance claims—from initial submission through policy validation, rule-based risk scoring, financial valuation, and settlement/approval.

The platform utilizes an **event-driven choreography and orchestration architecture** backed by **RabbitMQ**, asynchronous database operations in **MongoDB**, and **Redis** for distributed deduplication and idempotency.

---

## 1. System Architecture & Core Workflow

```
[ POST /api/v1/claims ]
           │
           ▼
┌─────────────────────────┐
│     claims-ingestion    │ ── (ClaimSubmitted) ──────────────────────────┐
└─────────────────────────┘                                               │
                                                                          ▼
┌─────────────────────────┐                                 ┌─────────────────────────┐
│     policy-service      │ ── (PolicyValidated) ─────────► │   claims-orchestrator   │
└─────────────────────────┘                                 │   - State Machine       │
           │                                                │   - Audit Logs          │
           ▼                                                │   - Manual Decisions    │
┌─────────────────────────┐                                 │   - Auto-Settlement     │
│   risk-scoring-service  │ ── (RiskScored) ──────────────► │                         │
└─────────────────────────┘                                 └─────────────────────────┘
           │                                                              ▲
           ▼                                                              │
┌─────────────────────────┐                                               │
│ valuation-reserve-serv. │ ── (ValuationCompleted) ──────────────────────┘
└─────────────────────────┘

```

### Event Lifecycle & Transitions

1. **`ClaimSubmitted`**: The claim is accepted at ingestion, validated, stored in MongoDB (`claims`), and published to the message broker.
2. **`PolicyValidated` / `PolicyValidationFailed**`: Policy terms (`maxCoverageAmount`, `deductibleAmount`, `coPayRatio`, active coverage window) are verified.
3. **`RiskScored`**: Rules evaluate coverage utilization (`claimedAmount / maxCoverageAmount`), attachment verification, and claim history. Claims are tiered into `LOW`, `MEDIUM`, or `HIGH` risk.
4. **`ValuationCompleted`**: Financial math calculates gross eligible payout, applies deductible and co-pay deductions, determines `netPayout`, and calculates actuarial loss reserves based on the assigned risk tier.
5. **Settlement / Review**:
* **Auto-Settlement**: If `requiresManualReview == false` and `netPayout > 0`, the claim advances to `SETTLEMENT_PENDING` and automatically resolves to `APPROVED` with a generated settlement reference.
* **Zero Payout**: If `netPayout <= 0` (e.g., claimed amount is below deductible), the claim automatically resolves to `REJECTED`.
* **Manual Review**: High/medium risk flags or coverage utilization thresholds park the claim in `PENDING_MANUAL_REVIEW`, awaiting reviewer decisions via `/approve` or `/reject`.



---

## 2. Microservice Topology

| Service | Stack | Port | Responsibilities |
| --- | --- | --- | --- |
| **`claims-ingestion-service`** | Spring Boot / Java | `8081` | Ingests claims, enforces payload validation, persists to `claims`, publishes `ClaimSubmitted`. |
| **`policy-service`** | Python / FastAPI / aio-pika | `8083` | Validates policy existence, active status, coverage dates, and deductible limits. |
| **`risk-scoring-service`** | Python / FastAPI / aio-pika | `8084` | Evaluates coverage utilization ratios and attachment presence; assigns risk score, tier, and review flag. |
| **`valuation-reserve-service`** | Python / FastAPI / aio-pika | `8085` | Executes financial payout math (`gross - deductible - copay`) and recommends actuarial loss reserves. |
| **`claims-orchestrator`** | Spring Boot / Java | `8082` | Maintains central claim lifecycle status machine, distributed tracing (`correlationId`/`causationId`), audit trail, auto-settlement, and manual review endpoints. |

---

## 3. Distributed Systems & Reliability Patterns

* **Guaranteed Idempotency:** Redis keys (`*:dedup:event:{eventId}`) backed by persistent MongoDB collections (`processed_events`) ensure replay protection across all asynchronous consumers.
* **Traceability & Auditing:** Every event carries immutable `correlationId` and `causationId` attributes. Every state transition is recorded in the append-only `claim_audit_logs` collection.
* **Dead Letter Queues (DLQ):** Failed messages are routed to dedicated DLQs (e.g., `q.risk-scoring.dlq`, `q.valuation-reserve.dlq`) to prevent broker head-of-line blocking.
* **Transactional Outbox / Idempotent Producers:** Outbox document collections ensure reliable event emission decoupling local writes from message broker availability.
* **Data Consistency Fallbacks:** Downstream scoring and valuation services use resilient fallbacks against primary MongoDB collections (`claims`, `policies`) when payloads require supplementary context.

---

## 4. Financial & Risk Calculation Engine

### Valuation & Deductibles

$$\text{Eligible Gross} = \min(\text{Claimed Amount}, \text{Max Policy Coverage})$$

$$\text{Base Payout} = \max(0, \text{Eligible Gross} - \text{Deductible})$$

$$\text{CoPay Deduction} = \text{Base Payout} \times \text{CoPay Ratio}$$

$$\text{Estimated Net Payout} = \text{Base Payout} - \text{CoPay Deduction}$$

### Actuarial Reserve Factor

$$\text{Recommended Reserve} = \text{Estimated Net Payout} \times \text{Risk Multiplier}$$

* **LOW Risk:** $1.00\times$
* **MEDIUM Risk:** $1.05\times$
* **HIGH Risk:** $1.20\times$

### Risk Scoring Matrix

* **Base Score:** `15`
* **Coverage Utilization $\ge 85\%$:** `+30` (`HIGH_COVERAGE_UTILIZATION`)
* **Coverage Utilization $\ge 50\%$:** `+15` (`MODERATE_COVERAGE_UTILIZATION`)
* **No Supporting Attachments:** `+20` (`NO_SUPPORTING_ATTACHMENTS`)
* **Tiers:** `LOW` ($< 40$), `MEDIUM` ($40 - 74$), `HIGH` ($\ge 75$). Any tier $\ge \text{MEDIUM}$ sets `requiresManualReview = true`.

---

## 5. API Reference

### Claim Ingestion

```http
POST /api/v1/claims
Host: localhost:8081
Content-Type: application/json
Idempotency-Key: <unique-idempotency-key>
X-Correlation-Id: <unique-correlation-id>

{
  "policyId": "pol_998877",
  "policyholderId": "usr_12345",
  "claimType": "TRAVEL_DELAY",
  "incidentOccurredAt": "2026-11-18T14:15:00Z",
  "claimedAmount": {
    "amount": 18500.00,
    "currency": "INR"
  },
  "incidentDetails": {
    "flightNumber": "6E-204",
    "origin": "HYD",
    "destination": "DEL",
    "delayHours": 7,
    "reason": "Air traffic congestion"
  },
  "attachments": [
    {
      "documentId": "doc-bp-001",
      "docType": "BOARDING_PASS",
      "fileName": "boarding-pass.pdf",
      "contentType": "application/pdf",
      "url": "https://storage.local/docs/boarding-pass.pdf"
    }
  ]
}

```

### Orchestrator Lifecycle Queries

* **Get Claim State:**
```http
GET /api/v1/claims/{claimId}
Host: localhost:8082

```


* **Get Full Audit Trail:**
```http
GET /api/v1/claims/{claimId}/audit
Host: localhost:8082

```



### Manual Underwriting Decision

* **Approve Claim:**
```http
POST /api/v1/claims/{claimId}/approve
Host: localhost:8082
Content-Type: application/json

{
  "reason": "Verified supporting travel delay proof with carrier operations"
}

```


* **Reject Claim:**
```http
POST /api/v1/claims/{claimId}/reject
Host: localhost:8082
Content-Type: application/json

{
  "reason": "Delay duration falls below mandatory policy minimum"
}

```



---

## 6. Infrastructure & Local Development

### Prerequisites

* **Java 21 / 25** (Spring Boot services)
* **Python 3.11+** (FastAPI services)
* **Docker & Docker Compose** (RabbitMQ, Redis, MongoDB)

### Running Services

```bash
# Spring Boot services (Ingestion, Orchestrator)
./mvnw spring-boot:run

# Python microservices (Policy, Risk Scoring, Valuation)
uvicorn app.main:app --host 0.0.0.0 --port <PORT> --reload

```