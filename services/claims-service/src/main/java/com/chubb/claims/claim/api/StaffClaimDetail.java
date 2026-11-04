package com.chubb.claims.claim.api;

import com.chubb.claims.claim.domain.ClaimStatus;
import com.chubb.claims.claim.domain.ClaimType;
import com.chubb.claims.claim.domain.Line;
import com.chubb.claims.claim.domain.RejectReason;
import com.chubb.claims.claim.domain.ReserveStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Everything staff may see, including internal notes and the ledger summary. Staff-only type (D7). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StaffClaimDetail(
    UUID id, String claimNumber, String market, UUID policyId, String policyNumber, Line line,
    ClaimType claimType, ClaimStatus status, LocalDateTime incidentAtLocal, String marketZone,
    Instant reportedAt, boolean late, String lateReason, String contactPhone, String description,
    String currency, BigDecimal estimate, MotorDetails motor, PropertyDetails property,
    Person claimant, Person assignedOfficer, Ledger ledger, Deadlines deadlines, OpenQuestion openQuestion,
    Rejection rejection, List<InfoRequestView> infoRequests, List<TimelineEntry> timeline,
    List<String> allowedActions, BigDecimal yourAuthorityLimit, long version) {

  public record Person(UUID id, String name) {}

  public record Ledger(
      BigDecimal coverageLimit, BigDecimal deductible, BigDecimal reserve, ReserveStatus reserveStatus,
      BigDecimal paid, BigDecimal outstanding, BigDecimal incurred, BigDecimal approvedAmount) {}

  public record Deadlines(
      Instant pickupDueAt, Instant decisionDueAt, Instant firstPickedUpAt, Instant decidedAt, Instant closedAt,
      boolean overdue, boolean waitingOnClaimant) {}

  public record OpenQuestion(String question, Instant askedAt) {}

  public record Rejection(RejectReason reasonCode, String explanation) {}

  public record InfoRequestView(String question, Instant askedAt, String answer, Instant answeredAt) {}

  /** {@code visibility} is INTERNAL or CLAIMANT, so staff can see what the claimant sees. */
  public record TimelineEntry(
      String type, String visibility, String message, ClaimStatus fromStatus, ClaimStatus toStatus, Instant at,
      String actorName) {}
}
