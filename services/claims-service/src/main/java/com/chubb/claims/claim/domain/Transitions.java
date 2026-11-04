package com.chubb.claims.claim.domain;

import static com.chubb.claims.claim.domain.ClaimAction.*;
import static com.chubb.claims.claim.domain.ClaimStatus.*;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** The transition table of LIFECYCLE.md as data. A missing entry is an illegal action. */
public final class Transitions {
  private static final Map<ClaimStatus, Map<ClaimAction, ClaimStatus>> TABLE =
      new EnumMap<>(ClaimStatus.class);

  static {
    row(SUBMITTED, PICK_UP, UNDER_REVIEW, ASSIGN, UNDER_REVIEW);
    row(UNDER_REVIEW,
        ASSIGN, UNDER_REVIEW, ADD_NOTE, UNDER_REVIEW, REQUEST_INFO, AWAITING_INFO,
        ASSESS, ASSESSED, REJECT, REJECTED);
    row(AWAITING_INFO,
        ASSIGN, AWAITING_INFO, ADD_NOTE, AWAITING_INFO, ANSWER_INFO, UNDER_REVIEW);
    row(ASSESSED,
        ASSIGN, ASSESSED, ADD_NOTE, ASSESSED, REQUEST_INFO, AWAITING_INFO,
        ASSESS, ASSESSED, APPROVE, APPROVED, REJECT, REJECTED);
    row(APPROVED, ASSIGN, APPROVED, ADD_NOTE, APPROVED, SETTLE, SETTLED);
    row(SETTLED);
    row(REJECTED);
  }

  private Transitions() {}

  private static void row(ClaimStatus from, Object... pairs) {
    Map<ClaimAction, ClaimStatus> actions = new EnumMap<>(ClaimAction.class);
    for (int i = 0; i < pairs.length; i += 2) {
      actions.put((ClaimAction) pairs[i], (ClaimStatus) pairs[i + 1]);
    }
    TABLE.put(from, actions);
  }

  public static Optional<ClaimStatus> next(ClaimStatus from, ClaimAction action) {
    return Optional.ofNullable(TABLE.get(from).get(action));
  }

  public static ClaimStatus require(ClaimStatus from, ClaimAction action) {
    return next(from, action)
        .orElseThrow(() -> new DomainException(
            ErrorCode.ILLEGAL_TRANSITION, action + " is not allowed in " + from));
  }
}
