package com.chubb.claims.claim.domain;

import java.util.List;

/** The submission broke one or more rules; carries every violation so the form can show them all. */
public class SubmissionRejectedException extends RuntimeException {
  private final List<Violation> violations;

  public SubmissionRejectedException(List<Violation> violations) {
    super("Submission rejected");
    this.violations = List.copyOf(violations);
  }

  public List<Violation> violations() { return violations; }
}
