package com.chubb.claims.claim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AmountRulesTest {

  private static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  private static void assertInvalidAmount(Runnable r) {
    assertThatThrownBy(r::run)
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_AMOUNT));
  }

  // ---- provisional reserve: min(max(estimate - deductible, 0), limit) ----

  @Test
  void reserveIsZeroWhenEstimateBelowDeductible() {
    assertThat(ProvisionalReserve.compute(d("300"), d("500"), d("100000"))).isEqualByComparingTo("0");
    assertThat(ProvisionalReserve.compute(d("0"), d("500"), d("100000"))).isEqualByComparingTo("0");
  }

  @Test
  void reserveIsZeroWhenEstimateEqualsDeductible() {
    assertThat(ProvisionalReserve.compute(d("500"), d("500"), d("100000"))).isEqualByComparingTo("0");
  }

  @Test
  void reserveSubtractsTheDeductible() {
    assertThat(ProvisionalReserve.compute(d("1500"), d("500"), d("100000"))).isEqualByComparingTo("1000");
    assertThat(ProvisionalReserve.compute(d("1500.75"), d("500"), d("100000")))
        .isEqualByComparingTo("1000.75");
  }

  @Test
  void reserveIsCappedAtTheCoverageLimit() {
    assertThat(ProvisionalReserve.compute(d("200000"), d("500"), d("100000"))).isEqualByComparingTo("100000");
    assertThat(ProvisionalReserve.compute(d("100500"), d("500"), d("100000"))).isEqualByComparingTo("100000");
    assertThat(ProvisionalReserve.compute(d("100499.99"), d("500"), d("100000")))
        .isEqualByComparingTo("99999.99");
  }

  @Test
  void idrReserveStaysInWholeUnits() {
    BigDecimal r = ProvisionalReserve.compute(d("15000000"), d("1000000"), d("500000000"));
    assertThat(r).isEqualByComparingTo("14000000");
    assertThat(Money.fits(r, "IDR")).isTrue();
    assertThat(ProvisionalReserve.compute(d("900000"), d("1000000"), d("500000000")))
        .isEqualByComparingTo("0");
  }

  // ---- currency scale ----

  @Test
  void idrHasNoDecimalPlacesAndOthersHaveTwo() {
    assertThat(Money.scaleOf("IDR")).isZero();
    for (String c : new String[] {"SGD", "HKD", "MYR", "THB", "AUD"}) {
      assertThat(Money.scaleOf(c)).isEqualTo(2);
    }
    assertThat(Money.fits(d("1500000"), "IDR")).isTrue();
    assertThat(Money.fits(d("1500000.00"), "IDR")).isTrue();
    assertThat(Money.fits(d("1500000.5"), "IDR")).isFalse();
    assertThat(Money.fits(d("10.50"), "SGD")).isTrue();
    assertThat(Money.fits(d("10.5"), "SGD")).isTrue();
    assertThat(Money.fits(d("10.005"), "SGD")).isFalse();
    assertThat(Money.fits(d("1E+3"), "IDR")).isTrue();
  }

  @Test
  void normalizeSetsTheCurrencyScale() {
    assertThat(Money.normalize(d("10.5"), "SGD").toPlainString()).isEqualTo("10.50");
    assertThat(Money.normalize(d("1500000.00"), "IDR").toPlainString()).isEqualTo("1500000");
  }

  // ---- amount rules per action ----

  @Test
  void submitEstimateMustBeNonNegativeAndFitTheCurrency() {
    assertThatCode(() -> AmountRules.submitEstimate(d("0"), "SGD")).doesNotThrowAnyException();
    assertThatCode(() -> AmountRules.submitEstimate(d("12.34"), "SGD")).doesNotThrowAnyException();
    assertInvalidAmount(() -> AmountRules.submitEstimate(d("-0.01"), "SGD"));
    assertInvalidAmount(() -> AmountRules.submitEstimate(d("12.345"), "SGD"));
    assertInvalidAmount(() -> AmountRules.submitEstimate(d("12.5"), "IDR"));
    assertInvalidAmount(() -> AmountRules.submitEstimate(null, "SGD"));
  }

  @Test
  void assessReserveMustBeBetweenZeroAndTheLimit() {
    assertThatCode(() -> AmountRules.assessReserve(d("0"), d("1000"), "SGD")).doesNotThrowAnyException();
    assertThatCode(() -> AmountRules.assessReserve(d("1000"), d("1000"), "SGD")).doesNotThrowAnyException();
    assertInvalidAmount(() -> AmountRules.assessReserve(d("-1"), d("1000"), "SGD"));
    assertInvalidAmount(() -> AmountRules.assessReserve(d("1000.01"), d("1000"), "SGD"));
    assertInvalidAmount(() -> AmountRules.assessReserve(d("1.001"), d("1000"), "SGD"));
  }

  @Test
  void approveAmountMustBePositiveAndWithinTheLimit() {
    assertThatCode(() -> AmountRules.approve(d("0.01"), d("1000"), "SGD")).doesNotThrowAnyException();
    assertThatCode(() -> AmountRules.approve(d("1000"), d("1000"), "SGD")).doesNotThrowAnyException();
    assertInvalidAmount(() -> AmountRules.approve(d("0"), d("1000"), "SGD"));
    assertInvalidAmount(() -> AmountRules.approve(d("-5"), d("1000"), "SGD"));
    assertInvalidAmount(() -> AmountRules.approve(d("1000.01"), d("1000"), "SGD"));
    assertInvalidAmount(() -> AmountRules.approve(d("10.5"), d("1000"), "IDR"));
  }

  @Test
  void settleMustPayExactlyTheApprovedAmount() {
    assertThatCode(() -> AmountRules.settle(d("500.00"), d("500"), "SGD")).doesNotThrowAnyException();
    assertInvalidAmount(() -> AmountRules.settle(d("499.99"), d("500"), "SGD"));
    assertInvalidAmount(() -> AmountRules.settle(d("500.01"), d("500"), "SGD"));
    assertInvalidAmount(() -> AmountRules.settle(d("500"), null, "SGD"));
  }

  // ---- reject reasons ----

  @Test
  void rejectReasonsAreTheSpecifiedCodes() {
    assertThat(RejectReason.values())
        .extracting(Enum::name)
        .containsExactlyInAnyOrder(
            "NOT_COVERED", "POLICY_NOT_IN_FORCE", "EXCLUDED_CAUSE", "INSUFFICIENT_EVIDENCE",
            "BELOW_DEDUCTIBLE", "OTHER");
  }

  @Test
  void rejectionNeedsAReasonAndAClaimantVisibleExplanation() {
    assertThatCode(() -> RejectReason.requireValid(RejectReason.NOT_COVERED, "Not a covered peril."))
        .doesNotThrowAnyException();
    for (String bad : new String[] {null, "", "   "}) {
      assertThatThrownBy(() -> RejectReason.requireValid(RejectReason.OTHER, bad))
          .isInstanceOfSatisfying(
              DomainException.class,
              e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
    assertThatThrownBy(() -> RejectReason.requireValid(null, "text"))
        .isInstanceOf(DomainException.class);
  }
}
