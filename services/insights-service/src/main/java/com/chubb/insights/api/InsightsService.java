package com.chubb.insights.api;

import com.chubb.insights.api.InsightsDtos.*;
import com.chubb.insights.config.FxProperties;
import com.chubb.insights.query.InsightsQueries;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

@Service
public class InsightsService {
  public static final String BASIS = "CASE_RESERVES_EXCL_IBNR";

  private final InsightsQueries queries;
  private final Clock clock;
  private final FxProperties fx;

  InsightsService(InsightsQueries queries, Clock clock, FxProperties fx) {
    this.queries = queries;
    this.clock = clock;
    this.fx = fx;
  }

  /** IDR has no minor unit in this system (LIFECYCLE.md amount rules); everything else has 2. */
  static int scale(String currency) {
    return "IDR".equals(currency) ? 0 : 2;
  }

  static BigDecimal money(BigDecimal v, String currency) {
    return v.setScale(scale(currency), RoundingMode.HALF_UP);
  }

  public ExposureResponse exposure(List<String> markets) {
    Instant now = clock.instant();
    if (markets.isEmpty()) return new ExposureResponse(BASIS, null, now, List.of(), List.of(), usd(Map.of()));
    String[] m = markets.toArray(String[]::new);
    List<ExposureRow> rows = new ArrayList<>();
    Map<String, BigDecimal[]> totals = new TreeMap<>(); // currency -> [open, prov, assessed, paid]
    for (var r : queries.exposure(m)) {
      BigDecimal outstanding = r.provisional().add(r.assessed());
      rows.add(new ExposureRow(r.market(), r.line(), r.currency(), r.openClaims(), money(outstanding, r.currency()),
          money(r.provisional(), r.currency()), money(r.assessed(), r.currency()), money(r.paid(), r.currency()),
          money(r.paid().add(outstanding), r.currency())));
      BigDecimal[] t = totals.computeIfAbsent(r.currency(), c -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO,
          BigDecimal.ZERO, BigDecimal.ZERO});
      t[0] = t[0].add(BigDecimal.valueOf(r.openClaims()));
      t[1] = t[1].add(r.provisional());
      t[2] = t[2].add(r.assessed());
      t[3] = t[3].add(r.paid());
    }
    List<CurrencyTotal> byCurrency = new ArrayList<>();
    Map<String, BigDecimal[]> forUsd = new TreeMap<>(); // currency -> [outstanding, paid, incurred]
    totals.forEach((c, t) -> {
      BigDecimal out = t[1].add(t[2]);
      byCurrency.add(new CurrencyTotal(c, t[0].longValue(), money(out, c), money(t[1], c), money(t[2], c),
          money(t[3], c), money(t[3].add(out), c)));
      forUsd.put(c, new BigDecimal[] {out, t[3], t[3].add(out)});
    });
    return new ExposureResponse(BASIS, queries.asOf(m), now, rows, byCurrency, usd(forUsd));
  }

  private IndicativeUsdTotal usd(Map<String, BigDecimal[]> byCurrency) {
    BigDecimal out = BigDecimal.ZERO, paid = BigDecimal.ZERO, inc = BigDecimal.ZERO;
    Map<String, BigDecimal> rates = new LinkedHashMap<>();
    List<String> excluded = new ArrayList<>();
    for (var e : byCurrency.entrySet()) {
      BigDecimal rate = fx.usdPerUnit().get(e.getKey());
      if (rate == null) {
        excluded.add(e.getKey());
        continue;
      }
      rates.put(e.getKey(), rate.stripTrailingZeros());
      out = out.add(e.getValue()[0].multiply(rate));
      paid = paid.add(e.getValue()[1].multiply(rate));
      inc = inc.add(e.getValue()[2].multiply(rate));
    }
    return new IndicativeUsdTotal("indicative", "USD", money(out, "USD"), money(paid, "USD"), money(inc, "USD"),
        rates, excluded, "Indicative only: fixed configured rates (USD per one unit), not market rates. "
            + "The native per-currency totals are the figures of record.");
  }

  public WorkloadResponse workload(List<String> markets) {
    Instant now = clock.instant();
    if (markets.isEmpty()) {
      return new WorkloadResponse(null, now, List.of(), new Team(0, 0, 0, 0), new AgeingBuckets(0, 0, 0, 0));
    }
    String[] m = markets.toArray(String[]::new);
    List<OfficerWorkload> officers = queries.officers(m, now).stream()
        .map(o -> new OfficerWorkload(o.officerId(), o.officerName(), o.open(), o.overdue(), o.waiting(),
            Math.max(0, Duration.between(o.oldestSubmittedAt(), now).toDays()), o.oldestSubmittedAt()))
        .toList();
    var t = queries.team(m, now);
    return new WorkloadResponse(queries.asOf(m), now, officers,
        new Team(t.open(), t.overdue(), t.waiting(), t.unassigned()),
        new AgeingBuckets(t.b0to2(), t.b3to7(), t.b8to14(), t.b15plus()));
  }

  public PerformanceResponse performance(List<String> markets, int days) {
    Instant now = clock.instant();
    Instant from = now.minus(Duration.ofDays(days));
    if (markets.isEmpty()) {
      return new PerformanceResponse(null, now, days, from, now, row(null, null, 0, 0, null, null, null, null),
          List.of(), List.of());
    }
    String[] m = markets.toArray(String[]::new);
    var byMarket = queries.performance(m, now, days, InsightsQueries.Grouping.MARKET).stream().map(InsightsService::row).toList();
    var byOfficer = queries.performance(m, now, days, InsightsQueries.Grouping.OFFICER).stream().map(InsightsService::row).toList();
    PerformanceRow overall = row(queries.performance(m, now, days, InsightsQueries.Grouping.ALL).get(0));
    return new PerformanceResponse(queries.asOf(m), now, days, from, now, overall, byMarket, byOfficer);
  }

  private static PerformanceRow row(InsightsQueries.PerfRow r) {
    return row(r.key(), r.name(), r.decided(), r.approved(), r.avgPickupSeconds(), r.medianPickupSeconds(),
        r.avgDecisionSeconds(), r.avgSettlementSeconds());
  }

  private static PerformanceRow row(String key, String name, long decided, long approved, Double avgPickup,
      Double medPickup, Double avgDecision, Double avgSettlement) {
    return new PerformanceRow(key, name, decided, decided == 0 ? null : round(approved / (double) decided, 4),
        hours(avgPickup), hours(medPickup), hours(avgDecision), hours(avgSettlement));
  }

  private static Double hours(Double seconds) {
    return seconds == null ? null : round(seconds / 3600.0, 2);
  }

  private static double round(double v, int places) {
    return BigDecimal.valueOf(v).setScale(places, RoundingMode.HALF_UP).doubleValue();
  }
}
