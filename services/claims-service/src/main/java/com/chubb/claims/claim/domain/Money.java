package com.chubb.claims.claim.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
  private Money() {}

  /** IDR has 0 decimal places in this system; every other currency has 2. */
  public static int scaleOf(String currency) {
    return "IDR".equals(currency) ? 0 : 2;
  }

  public static boolean fits(BigDecimal amount, String currency) {
    return amount.stripTrailingZeros().scale() <= scaleOf(currency);
  }

  /** Call only after {@link #fits}; it never rounds away a digit. */
  public static BigDecimal normalize(BigDecimal amount, String currency) {
    return amount.setScale(scaleOf(currency), RoundingMode.UNNECESSARY);
  }
}
