package com.chubb.claims.claim.api;

import com.chubb.claims.claim.domain.LedgerType;
import com.chubb.claims.claim.domain.ReserveStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Ledger entries plus the figures derived from them (LIFECYCLE.md, Ledger effects). */
public record StaffFinancials(
    String currency, BigDecimal coverageLimit, BigDecimal deductible, ReserveStatus reserveStatus,
    BigDecimal reserve, BigDecimal paid, BigDecimal outstanding, BigDecimal incurred, BigDecimal approvedAmount,
    List<Entry> entries, long version) {

  public record Entry(Long id, LedgerType type, BigDecimal amount, String reason, String actorName, Instant at) {}
}
