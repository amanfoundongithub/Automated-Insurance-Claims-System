from decimal import ROUND_HALF_UP, Decimal

from app.models import PolicyValidatedPayload


def compute_risk_score(payload: PolicyValidatedPayload) -> tuple[int, str, list[str], bool]:
    score = 15
    flags: list[str] = []

    claimed = payload.claimedAmount.amount if payload.claimedAmount else Decimal("0")
    max_cov = payload.maxCoverageAmount.amount if payload.maxCoverageAmount else Decimal("0")

    if max_cov > Decimal("0") and claimed > Decimal("0"):
        ratio = (claimed / max_cov).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)
        if ratio >= Decimal("0.85"):
            score += 30
            flags.append("HIGH_COVERAGE_UTILIZATION")
        elif ratio >= Decimal("0.50"):
            score += 15
            flags.append("MODERATE_COVERAGE_UTILIZATION")

    if payload.attachmentCount == 0:
        score += 20
        flags.append("NO_SUPPORTING_ATTACHMENTS")

    score = max(0, min(100, score))

    if score >= 75:
        tier = "HIGH"
    elif score >= 40:
        tier = "MEDIUM"
    else:
        tier = "LOW"

    requires_manual_review = tier != "LOW"
    return score, tier, flags, requires_manual_review