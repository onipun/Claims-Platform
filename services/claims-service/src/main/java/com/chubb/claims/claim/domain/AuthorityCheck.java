package com.chubb.claims.claim.domain;

import java.math.BigDecimal;

public final class AuthorityCheck {
  private AuthorityCheck() {}

  /** No {@code user_market} row for the claim's market means no authority, for any role (R-advisory). */
  public static void check(Actor actor, String market, BigDecimal amount) {
    if (!actor.hasRowIn(market)) {
      throw new DomainException(ErrorCode.AUTHORITY_EXCEEDED, "No approval authority in this market");
    }
    check(actor.role(), actor.authorityLimit(market), amount);
  }

  /** A null limit is unlimited for a manager only; an officer without a limit has no authority. */
  public static void check(Role role, BigDecimal limit, BigDecimal amount) {
    if (role == Role.MANAGER && limit == null) return;
    if (limit == null || amount.compareTo(limit) > 0) {
      throw new DomainException(ErrorCode.AUTHORITY_EXCEEDED, "Amount exceeds the approval authority");
    }
  }
}
