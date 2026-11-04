package com.chubb.insights.projection;

import com.chubb.insights.event.ClaimEvent;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The only writer of claim_projection. One statement, so it is atomic without a transaction. */
@Repository
public class ProjectionStore {
  private static final String UPSERT = """
      INSERT INTO claim_projection (claim_id, claim_number, market, line, claim_type, status, officer_id,
          officer_name, currency, reserve, reserve_status, paid, approved_amount, rejection_code, submitted_at,
          first_picked_up_at, decided_at, closed_at, pickup_due_at, decision_due_at, late, version, last_event_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (claim_id) DO UPDATE SET
          claim_number = excluded.claim_number, market = excluded.market, line = excluded.line,
          claim_type = excluded.claim_type, status = excluded.status, officer_id = excluded.officer_id,
          officer_name = excluded.officer_name, currency = excluded.currency, reserve = excluded.reserve,
          reserve_status = excluded.reserve_status, paid = excluded.paid,
          approved_amount = excluded.approved_amount, rejection_code = excluded.rejection_code,
          submitted_at = excluded.submitted_at, first_picked_up_at = excluded.first_picked_up_at,
          decided_at = excluded.decided_at, closed_at = excluded.closed_at, pickup_due_at = excluded.pickup_due_at,
          decision_due_at = excluded.decision_due_at, late = excluded.late, version = excluded.version,
          last_event_at = excluded.last_event_at
      WHERE excluded.version > claim_projection.version""";

  private final JdbcTemplate jdbc;

  ProjectionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** D4: applies the event only when its version is higher than the stored one. Returns whether it applied. */
  public boolean apply(ClaimEvent e) {
    var s = e.snapshot();
    return jdbc.update(UPSERT, e.claimId(), s.claimNumber(), s.market(), s.line(), s.claimType(), s.status(),
        s.officerId(), s.officerName(), s.currency(), s.reserve(), s.reserveStatus(), s.paid(), s.approvedAmount(),
        s.rejectionCode(), ts(s.submittedAt()), ts(s.firstPickedUpAt()), ts(s.decidedAt()), ts(s.closedAt()),
        ts(s.pickupDueAt()), ts(s.decisionDueAt()), s.late(), e.claimVersion(), ts(e.occurredAt())) > 0;
  }

  private static Timestamp ts(java.time.Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
