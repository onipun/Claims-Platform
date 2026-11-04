package com.chubb.claims.outbox;

import com.chubb.claims.claim.Claim;
import com.chubb.claims.claim.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The only claim data that leaves the service (trap 12). Built field by field from {@link Claim},
 * never by serialising the entity, so a new sensitive field cannot reach Kafka by accident.
 * Deliberately absent: claimant name, email and phone, description, loss details, notes, and any
 * free-text reason (the rejection <em>code</em> is allowed). Money is a plain-decimal string in
 * the claim currency (trap 8). "Waiting on claimant" is {@code status == AWAITING_INFO}.
 */
public record ClaimEventSnapshot(
    UUID claimId, String claimNumber, String market, String line, String claimType, String status,
    UUID officerId, String officerName, String currency, String reserve, String reserveStatus, String paid,
    String approvedAmount, String rejectionCode, String submittedAt, String firstPickedUpAt,
    String decidedAt, String closedAt, String pickupDueAt, String decisionDueAt, boolean late) {

  static ClaimEventSnapshot of(Claim c, String officerName) {
    String cur = c.getCurrency();
    return new ClaimEventSnapshot(c.getId(), c.getClaimNumber(), c.getMarketCode(), c.getLine().name(),
        c.getClaimType().name(), c.getStatus().name(), c.getAssignedOfficerId(), officerName, cur,
        money(c.getReserve(), cur), c.getReserveStatus().name(), money(c.getPaid(), cur),
        money(c.getApprovedAmount(), cur), c.getRejectionCode() == null ? null : c.getRejectionCode().name(),
        iso(c.getReportedAt()), iso(c.getFirstPickedUpAt()), iso(c.getDecidedAt()), iso(c.getClosedAt()),
        iso(c.getPickupDueAt()), iso(c.getDecisionDueAt()), c.isLate());
  }

  private static String money(BigDecimal v, String currency) {
    return v == null ? null : Money.normalize(v, currency).toPlainString();
  }

  private static String iso(Instant i) {
    return i == null ? null : i.toString();
  }
}
