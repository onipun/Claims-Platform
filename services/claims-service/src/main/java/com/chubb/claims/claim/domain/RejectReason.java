package com.chubb.claims.claim.domain;

public enum RejectReason {
  NOT_COVERED, POLICY_NOT_IN_FORCE, EXCLUDED_CAUSE, INSUFFICIENT_EVIDENCE, BELOW_DEDUCTIBLE, OTHER;

  /** Each rejection carries a claimant-visible explanation. */
  public static void requireValid(RejectReason reason, String explanation) {
    if (reason == null || explanation == null || explanation.isBlank()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "A reject reason and explanation are required");
    }
  }
}
