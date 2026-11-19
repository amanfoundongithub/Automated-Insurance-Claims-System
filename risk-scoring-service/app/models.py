from datetime import datetime, timezone
from decimal import Decimal
from typing import Any, Generic, Optional, TypeVar

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
    producer: str = "risk-scoring-service"
    correlationId: Optional[str] = None
    causationId: Optional[str] = None
    partitionKey: Optional[str] = None
    payload: T


class PolicyValidatedPayload(BaseModel):
    claimId: str
    policyId: str
    policyholderId: str
    claimedAmount: Optional[Money] = None
    maxCoverageAmount: Optional[Money] = None
    deductibleAmount: Optional[Money] = None
    coPayRatio: Optional[Decimal] = None
    attachmentCount: int = 0
    validatedAt: Optional[datetime] = None


class RiskScoredPayload(BaseModel):
    claimId: str
    policyId: str
    policyholderId: str
    riskScore: int
    riskTier: str
    riskFlags: list[str]
    requiresManualReview: bool
    scoredAt: datetime
    extraContext: dict[str, Any] = Field(default_factory=dict)