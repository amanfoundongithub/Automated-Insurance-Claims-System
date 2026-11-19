from datetime import datetime, timezone
from decimal import Decimal
from typing import Generic, Optional, TypeVar
from pydantic import BaseModel, Field

T = TypeVar("T")


class Money(BaseModel):
    amount: Decimal
    currency: str


class EventEnvelope(BaseModel, Generic[T]):
    eventId: str
    eventType: str
    eventVersion: str = "v1"
    occurredAt: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    producer: str = "valuation-reserve-service"
    correlationId: Optional[str] = None
    causationId: Optional[str] = None
    partitionKey: Optional[str] = None
    payload: T


class RiskScoredPayload(BaseModel):
    claimId: str
    policyId: str
    policyholderId: str
    riskScore: int
    riskTier: str
    riskFlags: list[str] = Field(default_factory=list)
    requiresManualReview: bool = False
    scoredAt: Optional[datetime] = None


class ValuationResult(BaseModel):
    currency: str
    claimedAmount: Decimal
    maxCoverageAmount: Decimal
    eligibleGrossAmount: Decimal
    deductibleAmount: Decimal
    coPayRatio: Decimal
    coPayDeductionAmount: Decimal
    estimatedNetPayout: Decimal
    recommendedReserve: Decimal