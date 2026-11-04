package com.chubb.claims.claim.domain;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Only SG MOTOR has a sourced rule (GIA). Public holidays are not modelled: it can only over-flag. */
public final class LateReportRule {
  private final Clock clock;

  public LateReportRule(Clock clock) {
    this.clock = clock;
  }

  public LateReport evaluate(String market, Line line, ZoneId marketZone, LocalDateTime incidentLocal) {
    if (!"SG".equals(market) || line != Line.MOTOR) return LateReport.notApplicable();
    LocalDate dueDay = incidentLocal.toLocalDate().plusDays(1);
    while (isWeekend(dueDay)) dueDay = dueDay.plusDays(1);
    // End of the due day in the market zone, exclusive: the first instant that is late.
    Instant lateFrom = dueDay.plusDays(1).atStartOfDay(marketZone).toInstant();
    return new LateReport(true, !clock.instant().isBefore(lateFrom), lateFrom);
  }

  private static boolean isWeekend(LocalDate d) {
    return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
  }
}
