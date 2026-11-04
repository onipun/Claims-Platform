package com.chubb.insights.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * All reads of the projection. Every query takes the already-resolved market list (token markets, narrowed by
 * the optional filter) and filters on it in SQL; nothing here knows about tokens. The SQL text is constant:
 * the only values are bind parameters.
 */
@Repository
public class InsightsQueries {
  private static final String OPEN = "status NOT IN ('SETTLED', 'REJECTED')";
  /** Same definition as claims-service StaffClaimMapper.overdue; the two {@code ?} are "now". */
  private static final String OVERDUE = """
      ((first_picked_up_at IS NULL AND ? > pickup_due_at)
        OR (decided_at IS NULL AND closed_at IS NULL AND ? > decision_due_at))""";
  private static final String AGE_DAYS = "floor(extract(epoch FROM (? - submitted_at)) / 86400)";

  private final JdbcTemplate jdbc;

  InsightsQueries(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record ExposureRow(String market, String line, String currency, long openClaims, BigDecimal provisional,
      BigDecimal assessed, BigDecimal paid) {}

  public record OfficerRow(UUID officerId, String officerName, long open, long overdue, long waiting,
      Instant oldestSubmittedAt) {}

  public record TeamRow(long open, long overdue, long waiting, long unassigned, long b0to2, long b3to7,
      long b8to14, long b15plus) {}

  public record PerfRow(String key, String name, long decided, long approved, Double avgPickupSeconds,
      Double medianPickupSeconds, Double avgDecisionSeconds, Double avgSettlementSeconds) {}

  public Instant asOf(String[] markets) {
    return jdbc.queryForObject("SELECT max(last_event_at) FROM claim_projection WHERE market = ANY (?)",
        (rs, i) -> rs.getObject(1, OffsetDateTime.class) == null ? null : rs.getObject(1, OffsetDateTime.class).toInstant(),
        (Object) markets);
  }

  /** Terminal claims contribute no outstanding; REJECTED contributes nothing at all (reserve 0, paid 0). */
  public List<ExposureRow> exposure(String[] markets) {
    return jdbc.query("""
        SELECT market, line, currency,
               count(*) FILTER (WHERE %1$s) AS open_claims,
               coalesce(sum(greatest(reserve - paid, 0)) FILTER (WHERE %1$s AND reserve_status = 'PROVISIONAL'), 0) AS provisional,
               coalesce(sum(greatest(reserve - paid, 0)) FILTER (WHERE %1$s AND reserve_status <> 'PROVISIONAL'), 0) AS assessed,
               coalesce(sum(paid) FILTER (WHERE status <> 'REJECTED'), 0) AS paid
        FROM claim_projection WHERE market = ANY (?)
        GROUP BY market, line, currency ORDER BY market, line, currency""".formatted(OPEN),
        (rs, i) -> new ExposureRow(rs.getString("market"), rs.getString("line"), rs.getString("currency"),
            rs.getLong("open_claims"), rs.getBigDecimal("provisional"), rs.getBigDecimal("assessed"),
            rs.getBigDecimal("paid")), (Object) markets);
  }

  public List<OfficerRow> officers(String[] markets, Instant now) {
    OffsetDateTime n = at(now);
    return jdbc.query("""
        SELECT officer_id, max(officer_name) AS officer_name, count(*) AS open,
               count(*) FILTER (WHERE %s) AS overdue,
               count(*) FILTER (WHERE status = 'AWAITING_INFO') AS waiting,
               min(submitted_at) AS oldest
        FROM claim_projection WHERE market = ANY (?) AND %s AND officer_id IS NOT NULL
        GROUP BY officer_id ORDER BY officer_name, officer_id""".formatted(OVERDUE, OPEN),
        (rs, i) -> new OfficerRow(rs.getObject("officer_id", UUID.class), rs.getString("officer_name"),
            rs.getLong("open"), rs.getLong("overdue"), rs.getLong("waiting"),
            rs.getObject("oldest", OffsetDateTime.class).toInstant()), n, n, markets);
  }

  public TeamRow team(String[] markets, Instant now) {
    OffsetDateTime n = at(now);
    return jdbc.queryForObject("""
        SELECT count(*) AS open,
               count(*) FILTER (WHERE %1$s) AS overdue,
               count(*) FILTER (WHERE status = 'AWAITING_INFO') AS waiting,
               count(*) FILTER (WHERE officer_id IS NULL) AS unassigned,
               count(*) FILTER (WHERE %2$s < 3) AS b0,
               count(*) FILTER (WHERE %2$s >= 3 AND %2$s < 8) AS b3,
               count(*) FILTER (WHERE %2$s >= 8 AND %2$s < 15) AS b8,
               count(*) FILTER (WHERE %2$s >= 15) AS b15
        FROM claim_projection WHERE market = ANY (?) AND %3$s""".formatted(OVERDUE, AGE_DAYS, OPEN),
        (rs, i) -> new TeamRow(rs.getLong("open"), rs.getLong("overdue"), rs.getLong("waiting"),
            rs.getLong("unassigned"), rs.getLong("b0"), rs.getLong("b3"), rs.getLong("b8"), rs.getLong("b15")),
        n, n, n, n, n, n, n, n, markets);
  }

  public enum Grouping { ALL, MARKET, OFFICER }

  /** OFFICER excludes unassigned claims; ALL is one row over the whole scope. */
  public List<PerfRow> performance(String[] markets, Instant now, int days, Grouping grouping) {
    boolean byOfficer = grouping == Grouping.OFFICER;
    OffsetDateTime to = at(now);
    OffsetDateTime from = to.minusDays(days);
    String key = switch (grouping) { case ALL -> "'ALL'"; case MARKET -> "market::text"; case OFFICER -> "officer_id::text"; };
    String name = byOfficer ? "max(officer_name)" : "NULL::text";
    String extra = byOfficer ? "AND officer_id IS NOT NULL" : "";
    String inPickup = "first_picked_up_at >= ? AND first_picked_up_at <= ?";
    String inDecided = "decided_at >= ? AND decided_at <= ?";
    String inSettled = "status = 'SETTLED' AND closed_at >= ? AND closed_at <= ?";
    return jdbc.query("""
        SELECT %1$s AS k, %2$s AS n,
               count(*) FILTER (WHERE %3$s) AS decided,
               count(*) FILTER (WHERE %3$s AND status <> 'REJECTED') AS approved,
               avg(extract(epoch FROM (first_picked_up_at - submitted_at))) FILTER (WHERE %4$s) AS avg_pickup,
               percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM (first_picked_up_at - submitted_at)))
                   FILTER (WHERE %4$s) AS median_pickup,
               avg(extract(epoch FROM (decided_at - submitted_at))) FILTER (WHERE %3$s) AS avg_decision,
               avg(extract(epoch FROM (closed_at - submitted_at))) FILTER (WHERE %5$s) AS avg_settlement
        FROM claim_projection WHERE market = ANY (?) %6$s
        %7$s ORDER BY 1""".formatted(key, name, inDecided, inPickup, inSettled, extra,
            switch (grouping) { case ALL -> ""; case MARKET -> "GROUP BY market"; case OFFICER -> "GROUP BY officer_id"; }),
        (rs, i) -> new PerfRow(rs.getString("k"), rs.getString("n"), rs.getLong("decided"), rs.getLong("approved"),
            dbl(rs, "avg_pickup"), dbl(rs, "median_pickup"),
            dbl(rs, "avg_decision"), dbl(rs, "avg_settlement")),
        from, to, from, to, from, to, from, to, from, to, from, to, markets);
  }

  private static Double dbl(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
    BigDecimal v = rs.getBigDecimal(col); // avg() is numeric, percentile_cont() is double: both convert
    return v == null ? null : v.doubleValue();
  }

  private static OffsetDateTime at(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }
}
