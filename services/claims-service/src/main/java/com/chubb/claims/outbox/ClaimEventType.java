package com.chubb.claims.outbox;

import java.util.Map;

/**
 * The event names of plan 6.3, plus {@code ClaimUpdated}: a staff note bumps the claim version
 * (trap 2), so LIFECYCLE invariant 8 demands an event for it. It carries the same snapshot and
 * changes nothing a read model shows, which is why it is a separate type and not a status change.
 */
public enum ClaimEventType {
  ClaimSubmitted, ClaimAssigned, ClaimStatusChanged, ClaimUpdated, ClaimAssessed, ClaimApproved,
  ClaimRejected, ClaimSettled;

  /** The names the command services already pass to the recorder. */
  private static final Map<String, ClaimEventType> BY_COMMAND = Map.of(
      "CLAIM_SUBMITTED", ClaimSubmitted,
      "CLAIM_ASSIGNED", ClaimAssigned,
      "NOTE_ADDED", ClaimUpdated,
      "INFO_REQUESTED", ClaimStatusChanged,
      "INFO_ANSWERED", ClaimStatusChanged,
      "CLAIM_ASSESSED", ClaimAssessed,
      "CLAIM_APPROVED", ClaimApproved,
      "CLAIM_REJECTED", ClaimRejected,
      "CLAIM_SETTLED", ClaimSettled);

  public static ClaimEventType fromCommand(String name) {
    ClaimEventType t = BY_COMMAND.get(name);
    if (t == null) throw new IllegalArgumentException("Unknown claim event name: " + name);
    return t;
  }
}
