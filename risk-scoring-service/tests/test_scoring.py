import unittest
from decimal import Decimal

from app.models import Money, PolicyValidatedPayload
from app.scoring import compute_risk_score


def make_payload(
    claimed: str | None = "100",
    maximum: str | None = "1000",
    attachments: int = 1,
) -> PolicyValidatedPayload:
    return PolicyValidatedPayload(
        claimId="claim-1",
        policyId="policy-1",
        policyholderId="holder-1",
        claimedAmount=Money(amount=Decimal(claimed), currency="USD") if claimed is not None else None,
        maxCoverageAmount=Money(amount=Decimal(maximum), currency="USD") if maximum is not None else None,
        attachmentCount=attachments,
    )


class ComputeRiskScoreTests(unittest.TestCase):
    def test_low_utilization_with_attachments_has_no_flags(self) -> None:
        self.assertEqual((15, "LOW", [], False), compute_risk_score(make_payload("100", "1000", 2)))

    def test_ratio_just_below_moderate_threshold_adds_no_utilization_flag(self) -> None:
        score, tier, flags, manual_review = compute_risk_score(make_payload("499.94", "1000", 1))

        self.assertEqual(15, score)
        self.assertEqual("LOW", tier)
        self.assertEqual([], flags)
        self.assertFalse(manual_review)

    def test_moderate_threshold_adds_moderate_flag(self) -> None:
        score, tier, flags, manual_review = compute_risk_score(make_payload("500", "1000", 1))

        self.assertEqual(30, score)
        self.assertEqual("LOW", tier)
        self.assertEqual(["MODERATE_COVERAGE_UTILIZATION"], flags)
        self.assertFalse(manual_review)

    def test_high_utilization_threshold_requires_manual_review(self) -> None:
        score, tier, flags, manual_review = compute_risk_score(make_payload("850", "1000", 1))

        self.assertEqual(45, score)
        self.assertEqual("MEDIUM", tier)
        self.assertEqual(["HIGH_COVERAGE_UTILIZATION"], flags)
        self.assertTrue(manual_review)

    def test_rounded_ratio_is_used_at_threshold(self) -> None:
        score, _, flags, _ = compute_risk_score(make_payload("849.96", "1000", 1))

        self.assertEqual(45, score)
        self.assertEqual(["HIGH_COVERAGE_UTILIZATION"], flags)

    def test_missing_attachments_add_flag_and_score(self) -> None:
        score, tier, flags, manual_review = compute_risk_score(make_payload("100", "1000", 0))

        self.assertEqual(35, score)
        self.assertEqual("LOW", tier)
        self.assertEqual(["NO_SUPPORTING_ATTACHMENTS"], flags)
        self.assertFalse(manual_review)

    def test_multiple_risk_factors_accumulate(self) -> None:
        score, tier, flags, manual_review = compute_risk_score(make_payload("900", "1000", 0))

        self.assertEqual(65, score)
        self.assertEqual("MEDIUM", tier)
        self.assertEqual(
            ["HIGH_COVERAGE_UTILIZATION", "NO_SUPPORTING_ATTACHMENTS"], flags
        )
        self.assertTrue(manual_review)

    def test_missing_amounts_and_zero_coverage_do_not_create_ratio_flag(self) -> None:
        score, _, flags, _ = compute_risk_score(make_payload(None, "0", 1))

        self.assertEqual(15, score)
        self.assertEqual([], flags)

    def test_missing_coverage_does_not_create_utilization_flag(self) -> None:
        score, tier, flags, manual_review = compute_risk_score(make_payload("900", None, 2))

        self.assertEqual(15, score)
        self.assertEqual("LOW", tier)
        self.assertEqual([], flags)
        self.assertFalse(manual_review)

    def test_moderate_utilization_without_attachments_requires_review(self) -> None:
        score, tier, flags, manual_review = compute_risk_score(make_payload("500", "1000", 0))

        self.assertEqual(50, score)
        self.assertEqual("MEDIUM", tier)
        self.assertEqual(
            ["MODERATE_COVERAGE_UTILIZATION", "NO_SUPPORTING_ATTACHMENTS"], flags
        )
        self.assertTrue(manual_review)

    def test_claim_amount_above_coverage_is_high_utilization(self) -> None:
        score, _, flags, _ = compute_risk_score(make_payload("1500", "1000", 1))

        self.assertEqual(45, score)
        self.assertEqual(["HIGH_COVERAGE_UTILIZATION"], flags)

    def test_zero_claim_amount_adds_no_utilization_risk(self) -> None:
        score, _, flags, _ = compute_risk_score(make_payload("0", "1000", 1))

        self.assertEqual(15, score)
        self.assertEqual([], flags)

    def test_positive_claim_with_zero_coverage_adds_no_utilization_flag(self) -> None:
        score, _, flags, _ = compute_risk_score(make_payload("100", "0", 1))

        self.assertEqual(15, score)
        self.assertEqual([], flags)

    def test_explicit_low_deductible_cushion_adds_severity_points(self) -> None:
        payload = PolicyValidatedPayload(
            claimId="claim-1",
            policyId="policy-1",
            policyholderId="holder-1",
            claimedAmount=Money(amount=Decimal("900"), currency="USD"),
            maxCoverageAmount=Money(amount=Decimal("1000"), currency="USD"),
            attachmentCount=1,
            deductibleAmount=Money(
                amount=Decimal("10"),
                currency="USD",
            ),
        )

        score, tier, flags, manual_review = compute_risk_score(payload)

        self.assertEqual(50, score)
        self.assertEqual("MEDIUM", tier)
        self.assertIn("LOW_DEDUCTIBLE_CUSHION", flags)
        self.assertTrue(manual_review)


if __name__ == "__main__":
    unittest.main()
