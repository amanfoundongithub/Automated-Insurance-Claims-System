from decimal import Decimal
import unittest

from app.valuation import calculate_valuation


class CalculateValuationTests(unittest.TestCase):
    def test_caps_claim_at_coverage_and_applies_deductible_and_copay(self) -> None:
        result = calculate_valuation(
            {"claimedAmount": {"amount": "1000", "currency": "USD"}},
            {
                "maxCoverageAmount": {"amount": "800", "currency": "USD"},
                "deductibleAmount": {"amount": "100", "currency": "USD"},
                "coPayRatio": "0.25",
            },
            "HIGH",
        )

        self.assertEqual(Decimal("800.00"), result.eligibleGrossAmount)
        self.assertEqual(Decimal("175.00"), result.coPayDeductionAmount)
        self.assertEqual(Decimal("525.00"), result.estimatedNetPayout)
        self.assertEqual(Decimal("603.75"), result.recommendedReserve)
        self.assertEqual("USD", result.currency)

    def test_claim_below_coverage_uses_claimed_amount(self) -> None:
        result = calculate_valuation(
            {"claimedAmount": {"amount": "250", "currency": "EUR"}},
            {"maxCoverageAmount": {"amount": "1000", "currency": "USD"}},
            "LOW",
        )

        self.assertEqual(Decimal("250.00"), result.eligibleGrossAmount)
        self.assertEqual("EUR", result.currency)

    def test_deductible_above_eligible_amount_floors_payout_at_zero(self) -> None:
        result = calculate_valuation(
            {"claimedAmount": {"amount": "50", "currency": "USD"}},
            {
                "maxCoverageAmount": {"amount": "100", "currency": "USD"},
                "deductibleAmount": {"amount": "75", "currency": "USD"},
            },
            "LOW",
        )

        self.assertEqual(Decimal("0.00"), result.estimatedNetPayout)
        self.assertEqual(Decimal("0.00"), result.recommendedReserve)

    def test_medium_risk_adds_five_percent_reserve(self) -> None:
        result = calculate_valuation(
            {"claimedAmount": {"amount": "100", "currency": "USD"}},
            {},
            "MEDIUM",
        )

        self.assertEqual(Decimal("105.00"), result.recommendedReserve)

    def test_unknown_risk_tier_uses_low_reserve_factor(self) -> None:
        result = calculate_valuation(
            {"claimedAmount": {"amount": "100", "currency": "USD"}},
            {},
            "unknown",
        )

        self.assertEqual(Decimal("100.00"), result.recommendedReserve)

    def test_copay_ratio_is_clamped_to_zero_and_one(self) -> None:
        zero_copay = calculate_valuation(
            {"claimedAmount": {"amount": "100", "currency": "USD"}},
            {"coPayRatio": "-0.5"},
            "LOW",
        )
        full_copay = calculate_valuation(
            {"claimedAmount": {"amount": "100", "currency": "USD"}},
            {"coPayRatio": "1.5"},
            "LOW",
        )

        self.assertEqual(Decimal("0.00"), zero_copay.coPayRatio)
        self.assertEqual(Decimal("100.00"), zero_copay.estimatedNetPayout)
        self.assertEqual(Decimal("1.00"), full_copay.coPayRatio)
        self.assertEqual(Decimal("0.00"), full_copay.estimatedNetPayout)

    def test_negative_amounts_are_clamped_to_zero(self) -> None:
        result = calculate_valuation(
            {"claimedAmount": {"amount": "-20", "currency": "USD"}},
            {
                "maxCoverageAmount": {"amount": "-10", "currency": "USD"},
                "deductibleAmount": {"amount": "-5", "currency": "USD"},
            },
            "LOW",
        )

        self.assertEqual(Decimal("0.00"), result.claimedAmount)
        self.assertEqual(Decimal("0.00"), result.maxCoverageAmount)
        self.assertEqual(Decimal("0.00"), result.deductibleAmount)
        self.assertEqual(Decimal("0.00"), result.estimatedNetPayout)

    def test_missing_documents_default_to_zero_and_inr(self) -> None:
        result = calculate_valuation(None, None, "LOW")

        self.assertEqual("INR", result.currency)
        self.assertEqual(Decimal("0.00"), result.claimedAmount)
        self.assertEqual(Decimal("0.00"), result.estimatedNetPayout)

    def test_currency_falls_back_to_policy_when_claim_currency_is_missing(self) -> None:
        result = calculate_valuation(
            {"claimedAmount": {"amount": "100"}},
            {"maxCoverageAmount": {"amount": "200", "currency": "GBP"}},
            "LOW",
        )

        self.assertEqual("GBP", result.currency)

    def test_missing_coverage_defaults_to_claimed_amount(self) -> None:
        result = calculate_valuation(
            {"claimedAmount": {"amount": "125.50", "currency": "CAD"}},
            {},
            "LOW",
        )

        self.assertEqual(Decimal("125.50"), result.maxCoverageAmount)
        self.assertEqual(Decimal("125.50"), result.eligibleGrossAmount)
        self.assertEqual("CAD", result.currency)

    def test_missing_copay_ratio_defaults_to_zero(self) -> None:
        result = calculate_valuation(
            {"claimedAmount": {"amount": "250", "currency": "USD"}},
            {"deductibleAmount": {"amount": "25", "currency": "USD"}},
            "LOW",
        )

        self.assertEqual(Decimal("0.00"), result.coPayRatio)
        self.assertEqual(Decimal("0.00"), result.coPayDeductionAmount)
        self.assertEqual(Decimal("225.00"), result.estimatedNetPayout)

    def test_missing_currencies_default_to_inr(self) -> None:
        result = calculate_valuation(
            {"claimedAmount": {"amount": "100"}},
            {"maxCoverageAmount": {"amount": "150"}},
            "LOW",
        )

        self.assertEqual("INR", result.currency)
        self.assertEqual(Decimal("100.00"), result.estimatedNetPayout)

    def test_none_risk_tier_uses_low_reserve_factor(self) -> None:
        result = calculate_valuation(
            {"claimedAmount": {"amount": "80", "currency": "USD"}},
            {},
            None,
        )

        self.assertEqual(Decimal("80.00"), result.recommendedReserve)

    def test_policy_currency_is_used_when_claim_document_is_missing(self) -> None:
        result = calculate_valuation(
            None,
            {"maxCoverageAmount": {"amount": "500", "currency": "AUD"}},
            "LOW",
        )

        self.assertEqual("AUD", result.currency)
        self.assertEqual(Decimal("0.00"), result.estimatedNetPayout)

    def test_deductible_defaults_to_zero_when_only_coverage_is_present(self) -> None:
        result = calculate_valuation(
            {"claimedAmount": {"amount": "90", "currency": "USD"}},
            {"maxCoverageAmount": {"amount": "100", "currency": "USD"}},
            "LOW",
        )

        self.assertEqual(Decimal("0.00"), result.deductibleAmount)
        self.assertEqual(Decimal("90.00"), result.estimatedNetPayout)

    def test_money_rounding_uses_half_up(self) -> None:
        result = calculate_valuation(
            {"claimedAmount": {"amount": "10.005", "currency": "USD"}},
            {"coPayRatio": "0.5"},
            "LOW",
        )

        self.assertEqual(Decimal("10.01"), result.eligibleGrossAmount)
        self.assertEqual(Decimal("5.01"), result.coPayDeductionAmount)
        self.assertEqual(Decimal("5.00"), result.estimatedNetPayout)


if __name__ == "__main__":
    unittest.main()
