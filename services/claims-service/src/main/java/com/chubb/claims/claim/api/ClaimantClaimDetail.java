package com.chubb.claims.claim.api;

import com.chubb.claims.claim.domain.ClaimStatus;
import com.chubb.claims.claim.domain.ClaimType;
import com.chubb.claims.claim.domain.Line;
import com.chubb.claims.claim.domain.RejectReason;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What a claimant may see of their own claim (D7). There is deliberately no field here for the
 * reserve, paid amount, internal notes, authority limits or an officer's id: a type that lacks the
 * field cannot leak it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClaimantClaimDetail(
    UUID id, String claimNumber, UUID policyId, String policyNumber, Line line, ClaimType claimType,
    ClaimStatus status, LocalDateTime incidentAtLocal, String marketZone, Instant reportedAt,
    boolean late, String lateReason, String contactPhone, String description,
    String currency, BigDecimal estimate, MotorDetails motor, PropertyDetails property,
    OpenQuestion openQuestion, DecisionView decision, boolean actionRequired,
    List<TimelineEntry> timeline, long version) {

  public record OpenQuestion(String question, Instant askedAt) {}

  /** Approved amount, or the rejection reason with its explanation. */
  public record DecisionView(
      String outcome, BigDecimal amount, String currency, RejectReason reasonCode, String explanation,
      Instant decidedAt) {}

  /** Display name only; never an id. */
  public record TimelineEntry(
      String type, String message, ClaimStatus fromStatus, ClaimStatus toStatus, Instant at, String actorName) {}
}
