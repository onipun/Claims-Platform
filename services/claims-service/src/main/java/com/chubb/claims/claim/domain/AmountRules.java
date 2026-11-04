package com.chubb.claims.claim.domain;

import java.math.BigDecimal;

public final class AmountRules {
  private AmountRules() {}

  public static void submitEstimate(BigDecimal estimate, String currency) {
    require(estimate != null && estimate.signum() >= 0 && Money.fits(estimate, currency),
        "Estimate must be zero or more, in the currency's minor units");
  }

  public static void assessReserve(BigDecimal reserve, BigDecimal limit, String currency) {
    require(reserve != null && reserve.signum() >= 0 && reserve.compareTo(limit) <= 0
        && Money.fits(reserve, currency), "Reserve must be between 0 and the coverage limit");
  }

  public static void approve(BigDecimal amount, BigDecimal limit, String currency) {
    require(amount != null && amount.signum() > 0 && amount.compareTo(limit) <= 0
        && Money.fits(amount, currency), "Approved amount must be above 0 and within the coverage limit");
  }

  /** Partial payments are out of scope: the payment must equal the approved amount. */
  public static void settle(BigDecimal paid, BigDecimal approved, String currency) {
    require(paid != null && approved != null && Money.fits(paid, currency)
        && paid.compareTo(approved) == 0, "Payment must equal the approved amount");
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new DomainException(ErrorCode.INVALID_AMOUNT, message);
  }
}
