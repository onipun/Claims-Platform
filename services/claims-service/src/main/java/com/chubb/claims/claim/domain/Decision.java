package com.chubb.claims.claim.domain;

import java.util.UUID;

/**
 * The outcome of a legal command. Holds the new values of everything the command may change, so
 * the JPA entity and the in-memory aggregate apply it the same way.
 */
public record Decision(
    ClaimAction action,
    ClaimStatus status,
    UUID assignedOfficerId,
    java.math.BigDecimal approvedAmount,
    /** Null when the reserve status does not change. */
    ReserveStatus reserveStatus,
    /** Null when the command writes no ledger entry. */
    LedgerEntry ledgerEntry,
    InfoChange infoChange,
    Visibility visibility,
    String message,
    RejectReason rejectReason,
    String rejectionExplanation) {

  public enum InfoChange { NONE, OPEN, ANSWER }

  public enum Visibility { INTERNAL, CLAIMANT }
}
