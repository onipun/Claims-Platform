package com.chubb.claims.claim.domain;

import java.time.Instant;

/** dueAt is null when no sourced rule applies to the market and line. */
public record LateReport(boolean applicable, boolean late, Instant dueAt) {
  static LateReport notApplicable() {
    return new LateReport(false, false, null);
  }
}
