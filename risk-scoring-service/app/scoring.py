from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal
from typing import Any

from app.models import PolicyValidatedPayload

ZERO = Decimal("0")
ONE = Decimal("1")
FOUR_DP = Decimal("0.0001")
TWO_DP = Decimal("0.01")
DEFAULT_CREDIBILITY_K = Decimal("10")
MIN_RECORDS_FOR_EMPIRICAL_K = 5


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


def estimate_credibility_k(
    grouped_amounts: Mapping[str, Sequence[Decimal | int | float | str]] | None,
    default_k: Decimal = DEFAULT_CREDIBILITY_K,
    min_records: int = MIN_RECORDS_FOR_EMPIRICAL_K,
    min_k: Decimal = ONE,
    max_k: Decimal = Decimal("100"),
) -> Decimal:
    """
    Estimates K = EPV / VHM from grouped historical claim amounts when total valid
    records >= `min_records` (default 5). Uses `default_k` when sample size is small
    or when within/between-group variance cannot be reliably estimated.
    """
    if not isinstance(grouped_amounts, Mapping):
        return default_k

    cleaned_groups: list[tuple[str, list[Decimal]]] = []
    total_records = 0

    for group_key, values in grouped_amounts.items():
        if not isinstance(values, Sequence) or isinstance(values, (str, bytes)):
            continue
        amounts: list[Decimal] = []
        for raw in values:
            if raw is None:
                continue
            try:
                amt = Decimal(str(raw))
            except Exception:
                continue
            if amt >= ZERO:
                amounts.append(amt)
        total_records += len(amounts)
        if len(amounts) >= 2:
            cleaned_groups.append((str(group_key), amounts))

    # Only compute empirical K when at least `min_records` (5) records exist across at least 2 groups
    if total_records < min_records or len(cleaned_groups) < 2:
        return default_k

    group_means: list[Decimal] = []
    group_counts: list[int] = []
    weighted_within_var_sum = ZERO
    total_dof = 0

    for _, amounts in cleaned_groups:
        n = len(amounts)
        mean = sum(amounts, ZERO) / Decimal(n)
        sq_diff = sum(((x - mean) ** 2 for x in amounts), ZERO)
        sample_var = sq_diff / Decimal(n - 1)

        group_means.append(mean)
        group_counts.append(n)
        weighted_within_var_sum += Decimal(n - 1) * sample_var
        total_dof += (n - 1)

    if total_dof <= 0:
        return default_k

    epv = weighted_within_var_sum / Decimal(total_dof)
    grand_mean = sum(group_means, ZERO) / Decimal(len(group_means))
    raw_between_var = sum(((m - grand_mean) ** 2 for m in group_means), ZERO) / Decimal(
        len(group_means) - 1
    )

    avg_group_n = sum((Decimal(c) for c in group_counts), ZERO) / Decimal(len(group_counts))
    sampling_noise = epv / avg_group_n if avg_group_n > ZERO else ZERO
    vhm = raw_between_var - sampling_noise

    if vhm <= Decimal("0.0001"):
        return default_k

    raw_k = epv / vhm
    clamped_k = min(max_k, max(min_k, raw_k))
    return clamped_k.quantize(FOUR_DP, rounding=ROUND_HALF_UP)


def _resolve_credibility_k(payload: PolicyValidatedPayload) -> Decimal:
    default_k = _extract_decimal(
        payload, "credibilityK", "credibility_k", default=DEFAULT_CREDIBILITY_K
    )
    if default_k <= ZERO:
        default_k = DEFAULT_CREDIBILITY_K

    grouped_history: Any = getattr(payload, "historicalAmountsByGroup", None)
    if grouped_history is None:
        grouped_history = getattr(payload, "historical_amounts_by_group", None)
    if grouped_history is None:
        grouped_history = getattr(payload, "claimHistoryByType", None)

    if grouped_history is not None:
        return estimate_credibility_k(grouped_history, default_k=default_k)

    return default_k


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
    credibility_k = _resolve_credibility_k(payload)

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
            "credibility_k": credibility_k,
        },
    )


def compute_risk_score(payload: PolicyValidatedPayload) -> tuple[int, str, list[str], bool]:
    result = evaluate_risk(payload)
    return result.score, result.tier, result.flags, result.requires_manual_review