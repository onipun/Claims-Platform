package com.chubb.claims.claim.domain;

public enum ClaimStatus {
  SUBMITTED, UNDER_REVIEW, AWAITING_INFO, ASSESSED, APPROVED, SETTLED, REJECTED;

  public boolean isTerminal() {
    return this == SETTLED || this == REJECTED;
  }
}
