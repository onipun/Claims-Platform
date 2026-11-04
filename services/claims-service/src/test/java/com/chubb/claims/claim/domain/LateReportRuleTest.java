package com.chubb.claims.claim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class LateReportRuleTest {

  private static final ZoneId SG = ZoneId.of("Asia/Singapore");
  private static final ZoneId HK = ZoneId.of("Asia/Hong_Kong");

  /** The server clock is deliberately UTC: trap 9 is a UTC server computing the wrong day. */
  private static LateReportRule reportedAt(String sgLocalTime) {
    Instant now = LocalDateTime.parse(sgLocalTime).atZone(SG).toInstant();
    return new LateReportRule(Clock.fixed(now, ZoneOffset.UTC));
  }

  private static LateReport sgMotor(String reportedAtSg, String incidentLocal) {
    return reportedAt(reportedAtSg)
        .evaluate("SG", Line.MOTOR, SG, LocalDateTime.parse(incidentLocal));
  }

  // 2026-11-07 is a Saturday.

  @Test
  void giaExample_saturdayNightAccident_reportedMonday_isNotLate() {
    LateReport r = sgMotor("2026-11-09T10:00:00", "2026-11-07T22:00:00");
    assertThat(r.applicable()).isTrue();
    assertThat(r.late()).isFalse();
    assertThat(r.dueAt()).isEqualTo(LocalDateTime.parse("2026-11-10T00:00:00").atZone(SG).toInstant());
  }

  @Test
  void giaExample_saturdayNightAccident_reportedLastSecondOfMonday_isNotLate() {
    assertThat(sgMotor("2026-11-09T23:59:59", "2026-11-07T22:00:00").late()).isFalse();
  }

  @Test
  void giaExample_saturdayNightAccident_reportedTuesday_isLate() {
    assertThat(sgMotor("2026-11-10T00:00:00", "2026-11-07T22:00:00").late()).isTrue();
    assertThat(sgMotor("2026-11-10T09:00:00", "2026-11-07T22:00:00").late()).isTrue();
  }

  @Test
  void fridayIncident_isDueEndOfMonday() {
    LateReport r = sgMotor("2026-11-09T12:00:00", "2026-11-06T18:00:00");
    assertThat(r.late()).isFalse();
    assertThat(r.dueAt()).isEqualTo(LocalDateTime.parse("2026-11-10T00:00:00").atZone(SG).toInstant());
    assertThat(sgMotor("2026-11-10T00:00:01", "2026-11-06T18:00:00").late()).isTrue();
  }

  @Test
  void sundayIncident_isDueEndOfMonday() {
    assertThat(sgMotor("2026-11-09T23:00:00", "2026-11-08T09:00:00").late()).isFalse();
    assertThat(sgMotor("2026-11-10T01:00:00", "2026-11-08T09:00:00").late()).isTrue();
  }

  @Test
  void midweekIncident_isDueEndOfNextDay() {
    // Wednesday 2026-11-04 -> due end of Thursday
    assertThat(sgMotor("2026-11-05T23:59:00", "2026-11-04T10:00:00").late()).isFalse();
    assertThat(sgMotor("2026-11-06T00:00:00", "2026-11-04T10:00:00").late()).isTrue();
  }

  /**
   * Singapore is UTC+8, so a 23:30 local incident has the same UTC date. The date only differs
   * between 00:00 and 08:00 local, so the zone case uses 00:30 on Monday, which is still Sunday
   * in UTC. A UTC-based rule would treat it as a Sunday incident (due Monday) and call a Tuesday
   * 10:00 report late. In the market zone it is a Monday incident, due end of Tuesday.
   */
  @Test
  void zoneHandling_incidentThatIsStillSundayInUtc_isMondayInSingapore() {
    String incident = "2026-11-09T00:30:00";
    assertThat(LocalDateTime.parse(incident).atZone(SG).withZoneSameInstant(ZoneOffset.UTC).getDayOfWeek())
        .isEqualTo(java.time.DayOfWeek.SUNDAY);

    LateReport r = sgMotor("2026-11-10T10:00:00", incident);
    assertThat(r.late()).isFalse();
    assertThat(r.dueAt()).isEqualTo(LocalDateTime.parse("2026-11-11T00:00:00").atZone(SG).toInstant());
    assertThat(sgMotor("2026-11-11T00:00:00", incident).late()).isTrue();
  }

  @Test
  void zoneHandling_lateEveningSingaporeIncident() {
    // Friday 23:30 SGT = Friday 15:30 UTC. Due Monday end either way, and the instant is exact.
    LateReport r = sgMotor("2026-11-09T23:59:00", "2026-11-06T23:30:00");
    assertThat(r.late()).isFalse();
    assertThat(r.dueAt())
        .isEqualTo(ZonedDateTime.of(2026, 11, 10, 0, 0, 0, 0, SG).toInstant());
  }

  @Test
  void clockZoneIsIrrelevant() {
    Instant now = LocalDateTime.parse("2026-11-09T10:00:00").atZone(SG).toInstant();
    for (ZoneId clockZone :
        new ZoneId[] {ZoneOffset.UTC, ZoneId.of("America/Los_Angeles"), ZoneId.of("Pacific/Kiritimati")}) {
      LateReportRule rule = new LateReportRule(Clock.fixed(now, clockZone));
      assertThat(rule.evaluate("SG", Line.MOTOR, SG, LocalDateTime.parse("2026-11-07T22:00:00")).late())
          .isFalse();
    }
  }

  @Test
  void marketsWithoutASourcedRule_neverFlag() {
    LateReportRule rule = reportedAt("2027-01-01T10:00:00");
    LateReport hk =
        rule.evaluate("HK", Line.MOTOR, HK, LocalDateTime.parse("2026-11-07T22:00:00"));
    assertThat(hk.applicable()).isFalse();
    assertThat(hk.late()).isFalse();
    assertThat(hk.dueAt()).isNull();
    assertThat(
            rule.evaluate("MY", Line.MOTOR, ZoneId.of("Asia/Kuala_Lumpur"), LocalDateTime.parse("2026-11-07T22:00:00"))
                .late())
        .isFalse();
  }

  @Test
  void sgPropertyHasNoSourcedRule() {
    LateReport r =
        reportedAt("2027-01-01T10:00:00")
            .evaluate("SG", Line.PROPERTY, SG, LocalDateTime.parse("2026-11-07T22:00:00"));
    assertThat(r.applicable()).isFalse();
    assertThat(r.late()).isFalse();
  }
}
