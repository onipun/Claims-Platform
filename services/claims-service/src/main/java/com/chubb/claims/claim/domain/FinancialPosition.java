package com.chubb.claims.claim.domain;

import java.math.BigDecimal;
import java.util.List;

/** Everything here is derived from the ledger; nothing is stored separately. */
public record FinancialPosition(BigDecimal reserve, BigDecimal paid) {

  public static FinancialPosition from(List<LedgerEntry> ledger) {
    BigDecimal reserve = BigDecimal.ZERO;
    BigDecimal paid = BigDecimal.ZERO;
    for (LedgerEntry e : ledger) {
      if (e.type() == LedgerType.RESERVE_SET) reserve = e.amount();
      else paid = paid.add(e.amount());
    }
    return new FinancialPosition(reserve, paid);
  }

  public BigDecimal outstanding() {
    return reserve.subtract(paid);
  }

  public BigDecimal incurred() {
    return paid.add(outstanding());
  }
}
