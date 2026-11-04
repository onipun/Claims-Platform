package com.chubb.claims.claim.domain;

import java.math.BigDecimal;

public final class ProvisionalReserve {
  private ProvisionalReserve() {}

  /** min(max(estimate - deductible, 0), limit): the deductible is the policyholder's, not the insurer's. */
  public static BigDecimal compute(BigDecimal estimate, BigDecimal deductible, BigDecimal limit) {
    return estimate.subtract(deductible).max(BigDecimal.ZERO).min(limit);
  }
}
