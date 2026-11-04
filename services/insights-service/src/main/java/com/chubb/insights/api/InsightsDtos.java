package com.chubb.insights.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Response shapes. Money is {@link BigDecimal}, serialised as a JSON string at the currency's scale. */
public final class InsightsDtos {
  private InsightsDtos() {}

  public record ExposureRow(String market, String line, String currency, long openClaims, BigDecimal caseOutstanding,
      BigDecimal provisional, BigDecimal assessed, BigDecimal paid, BigDecimal incurred) {}

  public record CurrencyTotal(String currency, long openClaims, BigDecimal caseOutstanding, BigDecimal provisional,
      BigDecimal assessed, BigDecimal paid, BigDecimal incurred) {}

  @Schema(description = "INDICATIVE only: native totals converted with the fixed configured rates listed here. "
      + "Not market rates and never used for a decision.")
  public record IndicativeUsdTotal(String label, String currency, BigDecimal caseOutstanding, BigDecimal paid,
      BigDecimal incurred, Map<String, BigDecimal> usdPerUnitRates, List<String> excludedCurrencies, String note) {}

  public record ExposureResponse(String basis, Instant asOf, Instant generatedAt, List<ExposureRow> rows,
      List<CurrencyTotal> totalsByCurrency, IndicativeUsdTotal indicativeUsdTotal) {}

  public record OfficerWorkload(UUID officerId, String officerName, long openClaims, long overdue,
      long waitingOnClaimant, Long oldestAgeDays, Instant oldestSubmittedAt) {}

  public record Team(long openClaims, long overdue, long waitingOnClaimant, long unassigned) {}

  public record AgeingBuckets(long days0to2, long days3to7, long days8to14, long days15plus) {}

  public record WorkloadResponse(Instant asOf, Instant generatedAt, List<OfficerWorkload> officers, Team team,
      AgeingBuckets ageing) {}

  @Schema(description = "Durations are in hours; null (not 0) where no claim qualifies. approvalRate is 0..1.")
  public record PerformanceRow(String key, String name, long decided, Double approvalRate, Double avgHoursToFirstPickup,
      Double medianHoursToFirstPickup, Double avgHoursToDecision, Double avgHoursToSettlement) {}

  public record PerformanceResponse(Instant asOf, Instant generatedAt, int days, Instant windowFrom, Instant windowTo,
      PerformanceRow overall, List<PerformanceRow> byMarket, List<PerformanceRow> byOfficer) {}
}
