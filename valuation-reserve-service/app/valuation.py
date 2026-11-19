from decimal import Decimal, ROUND_HALF_UP
from typing import Any, Optional
from app.models import ValuationResult


def _extract_money_amount(money_obj: Optional[dict[str, Any]], fallback: Decimal = Decimal("0.00")) -> tuple[Decimal, Optional[str]]:
    if not isinstance(money_obj, dict):
        return fallback, None
    raw_amount = money_obj.get("amount")
    currency = money_obj.get("currency")
    amount = Decimal(str(raw_amount)) if raw_amount is not None else fallback
    return amount, currency


def calculate_valuation(
    claim_doc: Optional[dict[str, Any]],
    policy_doc: Optional[dict[str, Any]],
    risk_tier: str,
) -> ValuationResult:
    claimed_amt, claim_ccy = _extract_money_amount(claim_doc.get("claimedAmount") if claim_doc else None)
    max_cov_amt, policy_ccy = _extract_money_amount(policy_doc.get("maxCoverageAmount") if policy_doc else None, fallback=claimed_amt)
    deductible_amt, _ = _extract_money_amount(policy_doc.get("deductibleAmount") if policy_doc else None, fallback=Decimal("0.00"))

    raw_copay = policy_doc.get("coPayRatio") if policy_doc and policy_doc.get("coPayRatio") is not None else Decimal("0.00")
    copay_ratio = Decimal(str(raw_copay))

    currency = claim_ccy or policy_ccy or "INR"

    claimed_amt = max(Decimal("0.00"), claimed_amt)
    max_cov_amt = max(Decimal("0.00"), max_cov_amt)
    deductible_amt = max(Decimal("0.00"), deductible_amt)
    copay_ratio = max(Decimal("0.00"), min(Decimal("1.00"), copay_ratio))

    # 1. Cap gross claim at policy max coverage
    eligible_gross = min(claimed_amt, max_cov_amt).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)

    # 2. Subtract policy deductible (floored at 0)
    after_deductible = max(Decimal("0.00"), eligible_gross - deductible_amt).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)

    # 3. Apply policyholder co-pay share; insurer pays (1 - coPayRatio)
    copay_deduction = (after_deductible * copay_ratio).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    net_payout = max(Decimal("0.00"), after_deductible - copay_deduction).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)

    # 4. Recommended reserve (slight buffer for MEDIUM/HIGH risk tiers)
    tier_upper = (risk_tier or "LOW").upper()
    if tier_upper == "HIGH":
        reserve_factor = Decimal("1.15")
    elif tier_upper == "MEDIUM":
        reserve_factor = Decimal("1.05")
    else:
        reserve_factor = Decimal("1.00")

    recommended_reserve = (net_payout * reserve_factor).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)

    return ValuationResult(
        currency=currency,
        claimedAmount=claimed_amt.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP),
        maxCoverageAmount=max_cov_amt.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP),
        eligibleGrossAmount=eligible_gross,
        deductibleAmount=deductible_amt.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP),
        coPayRatio=copay_ratio,
        coPayDeductionAmount=copay_deduction,
        estimatedNetPayout=net_payout,
        recommendedReserve=recommended_reserve,
    )