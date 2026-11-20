from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal
from typing import Any, Optional

from app.models import ValuationResult

ZERO = Decimal("0.00")
ONE = Decimal("1.00")
TWO_DP = Decimal("0.01")
FOUR_DP = Decimal("0.0001")


@dataclass(frozen=True)
class ValuationBreakdown:
    credibility_z: Decimal
    prior_reserve_factor: Decimal
    observed_reserve_factor: Decimal
    effective_reserve_factor: Decimal
    baseline_reserve: Decimal
    credibility_reserve: Decimal


def _extract_money_amount(
    money_obj: Optional[dict[str, Any]],
    fallback: Decimal = ZERO,
) -> tuple[Decimal, Optional[str]]:
    if not isinstance(money_obj, dict):
        return fallback, None
    raw_amount = money_obj.get("amount")
    currency = money_obj.get("currency")
    if raw_amount is None:
        return fallback, currency
    amount = Decimal(str(raw_amount))
    return amount, currency


def _extract_optional_decimal(doc: Optional[dict[str, Any]], *keys: str) -> Optional[Decimal]:
    if not isinstance(doc, dict):
        return None
    for key in keys:
        val = doc.get(key)
        if val is not None:
            try:
                return Decimal(str(val))
            except Exception:
                continue
    return None


def compute_credibility_z(exposure_count: Decimal, credibility_k: Decimal) -> Decimal:
    n = max(ZERO, exposure_count)
    k = max(FOUR_DP, credibility_k)
    if n <= ZERO:
        return ZERO.quantize(FOUR_DP, rounding=ROUND_HALF_UP)
    return (n / (n + k)).quantize(FOUR_DP, rounding=ROUND_HALF_UP)


def calculate_valuation_details(
    claim_doc: Optional[dict[str, Any]],
    policy_doc: Optional[dict[str, Any]],
    risk_tier: str,
) -> tuple[ValuationResult, ValuationBreakdown]:
    claimed_amt, claim_ccy = _extract_money_amount(
        claim_doc.get("claimedAmount") if claim_doc else None
    )
    max_cov_amt, policy_ccy = _extract_money_amount(
        policy_doc.get("maxCoverageAmount") if policy_doc else None,
        fallback=claimed_amt,
    )
    deductible_amt, _ = _extract_money_amount(
        policy_doc.get("deductibleAmount") if policy_doc else None,
        fallback=ZERO,
    )

    raw_copay = (
        policy_doc.get("coPayRatio")
        if policy_doc and policy_doc.get("coPayRatio") is not None
        else ZERO
    )
    copay_ratio = Decimal(str(raw_copay))

    currency = (claim_ccy or policy_ccy or "INR").strip().upper()

    claimed_amt = max(ZERO, claimed_amt)
    max_cov_amt = max(ZERO, max_cov_amt)
    deductible_amt = max(ZERO, deductible_amt)
    copay_ratio = max(ZERO, min(ONE, copay_ratio))

    # Optional sub-limit cap on claim_doc or policy_doc (e.g. per-incident / claimType cap)
    sub_limit_amt, _ = _extract_money_amount(
        claim_doc.get("subLimitAmount") if claim_doc else None,
        fallback=Decimal("0"),
    )
    if sub_limit_amt <= ZERO and policy_doc:
        sub_limit_amt, _ = _extract_money_amount(
            policy_doc.get("subLimitAmount"),
            fallback=Decimal("0"),
        )

    effective_gross_cap = max_cov_amt
    if sub_limit_amt > ZERO:
        effective_gross_cap = min(effective_gross_cap, sub_limit_amt)

    # 1. Cap gross claim at effective coverage / sub-limit
    eligible_gross = min(claimed_amt, effective_gross_cap).quantize(TWO_DP, rounding=ROUND_HALF_UP)

    # 2. Subtract policy deductible (floored at 0)
    after_deductible = max(ZERO, eligible_gross - deductible_amt).quantize(TWO_DP, rounding=ROUND_HALF_UP)

    # 3. Apply policyholder co-pay share; insurer pays (1 - coPayRatio)
    copay_deduction = (after_deductible * copay_ratio).quantize(TWO_DP, rounding=ROUND_HALF_UP)
    net_payout = max(ZERO, after_deductible - copay_deduction).quantize(TWO_DP, rounding=ROUND_HALF_UP)

    # 4. Tier prior reserve factor
    tier_upper = (risk_tier or "LOW").upper()
    if tier_upper == "HIGH":
        prior_factor = Decimal("1.15")
    elif tier_upper == "MEDIUM":
        prior_factor = Decimal("1.05")
    else:
        prior_factor = Decimal("1.00")

    baseline_reserve = (net_payout * prior_factor).quantize(TWO_DP, rounding=ROUND_HALF_UP)

    # 5. Optional credibility-weighted reserve factor: Z = n / (n + K)
    # Looks for optional priorExposureCount / priorClaimCount and credibilityK / observedReserveFactor
    exposure_n = _extract_optional_decimal(
        claim_doc, "priorExposureCount", "priorClaimCount", "exposureCount"
    )
    if exposure_n is None:
        exposure_n = _extract_optional_decimal(
            policy_doc, "priorExposureCount", "priorClaimCount", "exposureCount"
        ) or ZERO

    credibility_k = _extract_optional_decimal(claim_doc, "credibilityK")
    if credibility_k is None:
        credibility_k = _extract_optional_decimal(policy_doc, "credibilityK") or Decimal("10")

    observed_factor = _extract_optional_decimal(
        claim_doc, "observedReserveFactor", "historicalReserveFactor"
    )
    if observed_factor is None:
        observed_factor = _extract_optional_decimal(
            policy_doc, "observedReserveFactor", "historicalReserveFactor"
        ) or prior_factor

    observed_factor = max(Decimal("0.80"), min(Decimal("1.50"), observed_factor))
    credibility_z = compute_credibility_z(exposure_n, credibility_k)

    effective_factor = (
        credibility_z * observed_factor + (ONE - credibility_z) * prior_factor
    ).quantize(FOUR_DP, rounding=ROUND_HALF_UP)

    credibility_reserve = (net_payout * effective_factor).quantize(TWO_DP, rounding=ROUND_HALF_UP)

    # Use credibility_reserve when exposure > 0, otherwise keep exact baseline_reserve
    final_reserve = credibility_reserve if exposure_n > ZERO else baseline_reserve

    # Optional ULAE (Unallocated Loss Adjustment Expense) loading if configured (e.g. 0.03 = 3%)
    ulae_rate = _extract_optional_decimal(policy_doc, "ulaeRate") or ZERO
    ulae_rate = max(ZERO, min(Decimal("0.50"), ulae_rate))
    ulae_amount = (final_reserve * ulae_rate).quantize(TWO_DP, rounding=ROUND_HALF_UP)
    total_reserve = (final_reserve + ulae_amount).quantize(TWO_DP, rounding=ROUND_HALF_UP)

    breakdown = ValuationBreakdown(
        credibility_z=credibility_z,
        prior_reserve_factor=prior_factor,
        observed_reserve_factor=observed_factor,
        effective_reserve_factor=effective_factor,
        baseline_reserve=baseline_reserve,
        credibility_reserve=credibility_reserve,
    )

    result = ValuationResult(
        currency=currency,
        claimedAmount=claimed_amt.quantize(TWO_DP, rounding=ROUND_HALF_UP),
        maxCoverageAmount=max_cov_amt.quantize(TWO_DP, rounding=ROUND_HALF_UP),
        eligibleGrossAmount=eligible_gross,
        deductibleAmount=deductible_amt.quantize(TWO_DP, rounding=ROUND_HALF_UP),
        coPayRatio=copay_ratio,
        coPayDeductionAmount=copay_deduction,
        estimatedNetPayout=net_payout,
        recommendedReserve=total_reserve,
    )
    return result, breakdown


def calculate_valuation(
    claim_doc: Optional[dict[str, Any]],
    policy_doc: Optional[dict[str, Any]],
    risk_tier: str,
) -> ValuationResult:
    result, _ = calculate_valuation_details(claim_doc, policy_doc, risk_tier)
    return result