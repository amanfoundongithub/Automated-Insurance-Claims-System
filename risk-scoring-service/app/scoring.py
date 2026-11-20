from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal

from app.models import PolicyValidatedPayload

ZERO = Decimal("0")
ONE = Decimal("1")
FOUR_DP = Decimal("0.0001")
TWO_DP = Decimal("0.01")


@dataclass(frozen=True)
class RiskAssessment:
    score: int
    tier: str
    flags: list[str]
    requires_manual_review: bool
    credibility_z: Decimal
    breakdown: dict[str, Decimal | int]


def _extract_money_amount(obj: object, *attr_names: str) -> Decimal:
    """Safely extracts a Decimal amount from either a Money-like object (.amount) or numeric field."""
    for name in attr_names:
        val = getattr(obj, name, None)
        if val is None:
            continue
        inner = getattr(val, "amount", val)
        if inner is not None:
            return Decimal(str(inner))
    return ZERO


def _extract_int(obj: object, *attr_names: str, default: int = 0) -> int:
    for name in attr_names:
        val = getattr(obj, name, None)
        if val is not None:
            try:
                return int(val)
            except (TypeError, ValueError):
                continue
    return default


def _extract_decimal(obj: object, *attr_names: str, default: Decimal) -> Decimal:
    for name in attr_names:
        val = getattr(obj, name, None)
        if val is not None:
            try:
                return Decimal(str(val))
            except Exception:
                continue
    return default


def _has_explicit_deductible(payload: PolicyValidatedPayload) -> bool:
    return (
        getattr(payload, "deductibleAmount", None) is not None
        or getattr(payload, "deductible_amount", None) is not None
    )


def evaluate_risk(payload: PolicyValidatedPayload) -> RiskAssessment:
    score = 15
    flags: list[str] = []

    base_points = 15
    utilization_points = 0
    attachment_points = 0
    experience_points = 0
    severity_points = 0

    claimed = _extract_money_amount(payload, "claimedAmount", "claimed_amount")
    max_cov = _extract_money_amount(payload, "maxCoverageAmount", "max_coverage_amount")
    deductible = _extract_money_amount(payload, "deductibleAmount", "deductible_amount")
    baseline_claim = _extract_money_amount(
        payload, "baselineClaimAmount", "baseline_claim_amount", "averageClaimAmount", "average_claim_amount"
    )

    # 1. Coverage utilization (existing baseline)
    if max_cov > ZERO and claimed > ZERO:
        ratio = (claimed / max_cov).quantize(FOUR_DP, rounding=ROUND_HALF_UP)
        if ratio >= Decimal("0.85"):
            utilization_points = 30
            score += utilization_points
            flags.append("HIGH_COVERAGE_UTILIZATION")
        elif ratio >= Decimal("0.50"):
            utilization_points = 15
            score += utilization_points
            flags.append("MODERATE_COVERAGE_UTILIZATION")

    # 2. Attachment check (existing baseline)
    attachment_count = _extract_int(payload, "attachmentCount", "attachment_count", default=0)
    if attachment_count <= 0:
        attachment_points = 20
        score += attachment_points
        flags.append("NO_SUPPORTING_ATTACHMENTS")

    # 3. Credibility-weighted prior claim experience: Z = n / (n + K)
    prior_claims = max(0, _extract_int(payload, "priorClaimCount", "prior_claim_count", default=0))
    prior_adverse = max(
        0,
        _extract_int(
            payload,
            "priorAdverseCount",
            "prior_adverse_count",
            "priorRejectedOrHighRiskCount",
            default=prior_claims,
        ),
    )
    credibility_k = _extract_decimal(payload, "credibilityK", "credibility_k", default=Decimal("10"))
    if credibility_k <= ZERO:
        credibility_k = Decimal("10")

    n_dec = Decimal(prior_claims)
    if n_dec > ZERO:
        credibility_z = (n_dec / (n_dec + credibility_k)).quantize(FOUR_DP, rounding=ROUND_HALF_UP)
        # Adverse rate in [0, 1] scaled into up to +25 credibility-weighted experience points
        adverse_rate = min(ONE, Decimal(min(prior_adverse, prior_claims)) / n_dec)
        raw_exp_points = Decimal("25") * adverse_rate
        scaled_exp = (credibility_z * raw_exp_points).quantize(TWO_DP, rounding=ROUND_HALF_UP)
        experience_points = int(scaled_exp.quantize(Decimal("1"), rounding=ROUND_HALF_UP))
        if experience_points > 0:
            score += experience_points
            flags.append("CREDIBILITY_PRIOR_CLAIM_EXPERIENCE")
    else:
        credibility_z = ZERO.quantize(FOUR_DP, rounding=ROUND_HALF_UP)

    # 4. Severity & deductible-cushion signals (actuarial loss/exposure sensitivity)
    if claimed > ZERO and baseline_claim > ZERO:
        severity_ratio = (claimed / baseline_claim).quantize(FOUR_DP, rounding=ROUND_HALF_UP)
        if severity_ratio >= Decimal("2.00"):
            severity_points += 15
            flags.append("SEVERE_CLAIM_OUTLIER")
        elif severity_ratio >= Decimal("1.50"):
            severity_points += 8
            flags.append("ELEVATED_CLAIM_SEVERITY")

    # If a high-utilization claim has a very small deductible cushion (< 5% of claimed amount),
    # a larger share of the loss passes straight through to payout/reserve.
    if claimed > ZERO and deductible >= ZERO and _has_explicit_deductible(payload):
        deductible_ratio = (deductible / claimed).quantize(FOUR_DP, rounding=ROUND_HALF_UP)
        if deductible_ratio < Decimal("0.05") and utilization_points > 0:
            severity_points += 5
            flags.append("LOW_DEDUCTIBLE_CUSHION")

    score += severity_points
    clamped_score = max(0, min(100, score))

    if clamped_score >= 75:
        tier = "HIGH"
    elif clamped_score >= 40:
        tier = "MEDIUM"
    else:
        tier = "LOW"

    requires_manual_review = tier != "LOW"

    return RiskAssessment(
        score=clamped_score,
        tier=tier,
        flags=flags,
        requires_manual_review=requires_manual_review,
        credibility_z=credibility_z,
        breakdown={
            "base": base_points,
            "utilization": utilization_points,
            "attachments": attachment_points,
            "experience": experience_points,
            "severity": severity_points,
        },
    )


def compute_risk_score(payload: PolicyValidatedPayload) -> tuple[int, str, list[str], bool]:
    result = evaluate_risk(payload)
    return result.score, result.tier, result.flags, result.requires_manual_review