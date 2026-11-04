package com.chubb.claims.claim.domain;

public enum ErrorCode {
  ILLEGAL_TRANSITION(409),
  INVALID_AMOUNT(422),
  INVALID_ASSIGNEE(422),
  VALIDATION_FAILED(422),
  POLICY_MISMATCH(422),
  INVALID_CLAIM_TYPE(422),
  INCIDENT_IN_FUTURE(422),
  INCIDENT_OUTSIDE_POLICY_PERIOD(422),
  AUTHORITY_EXCEEDED(403),
  CONFLICT_OF_INTEREST(403),
  /** Also used where the caller must not learn that the object exists. */
  NOT_FOUND(404);

  private final int httpStatus;

  ErrorCode(int httpStatus) {
    this.httpStatus = httpStatus;
  }

  public int httpStatus() {
    return httpStatus;
  }
}
