package com.chubb.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.insights.support.InsightsTestBase;
import com.chubb.insights.support.TestTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exact figures, scoping and auth, with a fixed clock (T0 = 2026-06-15T12:00:00Z) and hand-computed fixtures.
 *
 * <pre>
 * id  mkt line      status       officer reserve(status)    paid     submitted  picked  decided closed  lastEvent
 * c1  SG  MOTOR     SUBMITTED    -       1000.00 PROV       0        T-1d       -       -       -       T-10m
 * c2  SG  MOTOR     UNDER_REVIEW Priya   2000.50 PROV       0        T-3d       +2h     -       -       T-5m
 * c3  SG  MOTOR     AWAITING_INFO Priya  3000.00 PROV       0        T-7d       +4h     -       -       T-10m
 * c4  SG  PROPERTY  ASSESSED     Wei     4000.00 ASSESSED   0        T-8d       +6h     -       -       T-10m
 * c5  SG  MOTOR     APPROVED     Wei     5000.00 ASSESSED   0        T-14d      +8h     T-1d    -       T-10m
 * c6  SG  MOTOR     UNDER_REVIEW Wei     6000.00 ASSESSED   0        T-15d      +10h    -       -       T-10m
 * c7  SG  MOTOR     SETTLED      Priya   7000.00 ASSESSED   7000.00  T-20d      +12h    T-10d   T-5d    T-10m
 * c8  SG  MOTOR     REJECTED     Priya   0.00 ASSESSED      0        T-12d      +1h     T-11d   T-11d   T-10m
 * c9  SG  MOTOR     SUBMITTED    -       500.00 PROV        0        T-2d23h    -       -       -       T-10m
 * h1  HK  MOTOR     UNDER_REVIEW Hana    10000.00 PROV      0        T-1d       +3h     -       -       T-10m
 * h2  HK  MOTOR     SETTLED      Hana    2000.00 ASSESSED   2000.00  T-6d       +5h     T-5d    T-4d    T-1m
 * i1  ID  PROPERTY  UNDER_REVIEW Budi    15000000 PROV      0        T-4d       +7h     -       -       T-2m
 * i2  ID  MOTOR     SETTLED      Budi    2500000 ASSESSED   2500000  T-10d      +9h     T-8d    T-7d    T-10m
 *
 * pickup due = submitted + 24h, decision due = submitted + 14d.
 * Overdue: c6 (decision due T-1d) and c9 (pickup due T-1d23h). Not overdue: c1 (pickup due == now, not after),
 * c5 (decision due == now, and decided anyway).
 *
 * EXPOSURE (open = not SETTLED/REJECTED; outstanding = reserve - paid, split by reserve status)
 *  SG MOTOR    open 6: PROV 1000.00+2000.50+3000.00+500.00 = 6500.50; ASSESSED 5000+6000 = 11000.00;
 *              outstanding 17500.50; paid 7000.00 (c7; c8 rejected = 0); incurred 24500.50
 *  SG PROPERTY open 1: PROV 0.00; ASSESSED 4000.00; outstanding 4000.00; paid 0.00; incurred 4000.00
 *  HK MOTOR    open 1: PROV 10000.00; outstanding 10000.00; paid 2000.00; incurred 12000.00
 *  ID PROPERTY open 1: PROV 15000000; outstanding 15000000; paid 0; incurred 15000000
 *  ID MOTOR    open 0: outstanding 0; paid 2500000; incurred 2500000
 *  Totals SGD: open 7, PROV 6500.50, ASSESSED 15000.00, outstanding 21500.50, paid 7000.00, incurred 28500.50
 *         HKD: open 1, PROV 10000.00, ASSESSED 0.00, outstanding 10000.00, paid 2000.00, incurred 12000.00
 *         IDR: open 1, PROV 15000000, ASSESSED 0, outstanding 15000000, paid 2500000, incurred 17500000
 *  Indicative USD (SGD 0.74, HKD 0.128, IDR 0.000062):
 *     outstanding 21500.50*0.74 + 10000*0.128 + 15000000*0.000062 = 15910.37 + 1280 + 930 = 18120.37
 *     paid        7000*0.74 + 2000*0.128 + 2500000*0.000062 = 5180 + 256 + 155 = 5591.00
 *     incurred    28500.50*0.74 + 12000*0.128 + 17500000*0.000062 = 21090.37 + 1536 + 1085 = 23711.37
 *
 * WORKLOAD (open claims; age = whole days since submitted)
 *  Priya open 2 (c2, c3) overdue 0 waiting 1 oldest 7d | Wei open 3 (c4,c5,c6) overdue 1 oldest 15d
 *  Hana open 1 (h1) oldest 1d | Budi open 1 (i1) oldest 4d
 *  Team: open 9, overdue 2 (c6, c9), waiting 1 (c3), unassigned 2 (c1, c9)
 *  Ageing: 0-2d: c1(1d) c9(2d23h) h1(1d) = 3 | 3-7d: c2(3d) c3(7d) i1(4d) = 3 | 8-14d: c4(8d) c5(14d) = 2 | 15d+: c6 = 1
 *
 * PERFORMANCE days=30 (window T-30d..T0). Hours submitted->first pickup: c2 2, c3 4, c4 6, c5 8, c6 10, c7 12,
 * c8 1, h1 3, h2 5, i1 7, i2 9. Decision cycle: c5 13d=312h, c7 10d=240h, c8 1d=24h, h2 1d=24h, i2 2d=48h.
 * Settlement cycle: c7 15d=360h, h2 3d... closed T-4d - T-6d = 2d = 48h, i2 closed T-7d - T-10d = 3d = 72h.
 *  SG: decided 3 (c5,c7,c8) approved 2 -> 0.6667; pickup avg 43/7 = 6.14, median 6.0; decision 576/3 = 192.0; settlement 360.0
 *  HK: decided 1, rate 1.0; pickup avg 4.0 median 4.0; decision 24.0; settlement 48.0
 *  ID: decided 1, rate 1.0; pickup avg 8.0 median 8.0; decision 48.0; settlement 72.0
 *  ALL: decided 5, approved 4 -> 0.8; pickup 67/11 = 6.09, median 6.0; decision 648/5 = 129.6; settlement 480/3 = 160.0
 *  Priya: pickups 2,4,12,1 avg 4.75 median 3.0; decided 2 (c7,c8) rate 0.5; decision 132.0; settlement 360.0
 *  Wei: pickups 6,8,10 avg 8.0 median 8.0; decided 1 (c5) rate 1.0; decision 312.0; settlement null
 * </pre>
 */
class InsightsApiTest extends InsightsTestBase {
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID PRIYA = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID WEI = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID HANA = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID BUDI = UUID.fromString("00000000-0000-0000-0000-000000000004");

  @LocalServerPort int port;

  private static Instant ago(String d) { // "3d", "2d23h", "10m"
    Duration total = Duration.ZERO;
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)([dhm])").matcher(d);
    while (m.find()) {
      long n = Long.parseLong(m.group(1));
      total = total.plus(switch (m.group(2)) { case "d" -> Duration.ofDays(n); case "h" -> Duration.ofHours(n); default -> Duration.ofMinutes(n); });
    }
    return T0.minus(total);
  }

  private void claim(String market, String line, String status, UUID officer, String officerName, String currency,
      String reserve, String reserveStatus, String paid, String submittedAgo, Long pickedHoursAfter,
      String decidedAgo, String closedAgo, String lastEventAgo) {
    Instant sub = ago(submittedAgo);
    jdbc.update("""
        INSERT INTO claim_projection (claim_id, claim_number, market, line, claim_type, status, officer_id, officer_name,
          currency, reserve, reserve_status, paid, submitted_at, first_picked_up_at, decided_at, closed_at,
          pickup_due_at, decision_due_at, late, version, last_event_at)
        VALUES (?, ?, ?, ?, 'OTHER', ?, ?, ?, ?, ?::numeric, ?, ?::numeric, ?, ?, ?, ?, ?, ?, false, 1, ?)""",
        UUID.randomUUID(), "CLM-" + market + "-" + UUID.randomUUID().toString().substring(0, 6), market, line, status,
        officer, officerName, currency, reserve, reserveStatus, paid, Timestamp.from(sub),
        pickedHoursAfter == null ? null : Timestamp.from(sub.plus(Duration.ofHours(pickedHoursAfter))),
        decidedAgo == null ? null : Timestamp.from(ago(decidedAgo)), closedAgo == null ? null : Timestamp.from(ago(closedAgo)),
        Timestamp.from(sub.plus(Duration.ofHours(24))), Timestamp.from(sub.plus(Duration.ofDays(14))),
        Timestamp.from(ago(lastEventAgo)));
  }

  @BeforeEach
  void fixture() {
    claim("SG", "MOTOR", "SUBMITTED", null, null, "SGD", "1000.00", "PROVISIONAL", "0", "1d", null, null, null, "10m");
    claim("SG", "MOTOR", "UNDER_REVIEW", PRIYA, "Priya", "SGD", "2000.50", "PROVISIONAL", "0", "3d", 2L, null, null, "5m");
    claim("SG", "MOTOR", "AWAITING_INFO", PRIYA, "Priya", "SGD", "3000.00", "PROVISIONAL", "0", "7d", 4L, null, null, "10m");
    claim("SG", "PROPERTY", "ASSESSED", WEI, "Wei", "SGD", "4000.00", "ASSESSED", "0", "8d", 6L, null, null, "10m");
    claim("SG", "MOTOR", "APPROVED", WEI, "Wei", "SGD", "5000.00", "ASSESSED", "0", "14d", 8L, "1d", null, "10m");
    claim("SG", "MOTOR", "UNDER_REVIEW", WEI, "Wei", "SGD", "6000.00", "ASSESSED", "0", "15d", 10L, null, null, "10m");
    claim("SG", "MOTOR", "SETTLED", PRIYA, "Priya", "SGD", "7000.00", "ASSESSED", "7000.00", "20d", 12L, "10d", "5d", "10m");
    claim("SG", "MOTOR", "REJECTED", PRIYA, "Priya", "SGD", "0.00", "ASSESSED", "0", "12d", 1L, "11d", "11d", "10m");
    claim("SG", "MOTOR", "SUBMITTED", null, null, "SGD", "500.00", "PROVISIONAL", "0", "2d23h", null, null, null, "10m");
    claim("HK", "MOTOR", "UNDER_REVIEW", HANA, "Hana", "HKD", "10000.00", "PROVISIONAL", "0", "1d", 3L, null, null, "10m");
    claim("HK", "MOTOR", "SETTLED", HANA, "Hana", "HKD", "2000.00", "ASSESSED", "2000.00", "6d", 5L, "5d", "4d", "1m");
    claim("ID", "PROPERTY", "UNDER_REVIEW", BUDI, "Budi", "IDR", "15000000", "PROVISIONAL", "0", "4d", 7L, null, null, "2m");
    claim("ID", "MOTOR", "SETTLED", BUDI, "Budi", "IDR", "2500000", "ASSESSED", "2500000", "10d", 9L, "8d", "7d", "10m");
  }

  private record Resp(int status, String body, String contentType) {
    JsonNode json() { return JSON.readTree(body); }
  }

  private Resp get(String path, String token) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (token != null) b.header("Authorization", "Bearer " + token);
    HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new Resp(r.statusCode(), r.body(), r.headers().firstValue("Content-Type").orElse(""));
  }

  private static String manager() { return TestTokens.token("MANAGER", "SG", "HK", "ID"); }
  private static String sgOfficer() { return TestTokens.token("OFFICER", "SG"); }

  private static JsonNode find(JsonNode array, String field, String value) {
    for (JsonNode n : array) if (value.equals(n.path(field).asString())) return n;
    throw new AssertionError(field + "=" + value + " not in " + array);
  }

  private static void money(JsonNode row, String... kv) {
    for (int i = 0; i < kv.length; i += 2) {
      assertThat(row.path(kv[i]).isString()).as(kv[i] + " is a JSON string").isTrue();
      assertThat(row.path(kv[i]).asString()).as(kv[i]).isEqualTo(kv[i + 1]);
    }
  }

  // ---- exposure ----

  @Test
  void exposureEveryNumber() throws Exception {
    JsonNode j = get("/api/v1/insights/exposure", manager()).json();
    assertThat(j.path("basis").asString()).isEqualTo("CASE_RESERVES_EXCL_IBNR");
    assertThat(j.path("asOf").asString()).isEqualTo("2026-06-15T11:59:00Z"); // h2, T-1m
    assertThat(j.path("generatedAt").asString()).isEqualTo("2026-06-15T12:00:00Z");
    assertThat(j.path("rows").size()).isEqualTo(5);

    // ordered by market, line: HK MOTOR, ID MOTOR, ID PROPERTY, SG MOTOR, SG PROPERTY
    JsonNode sgm = j.path("rows").get(3);
    assertThat(sgm.path("market").asString()).isEqualTo("SG");
    assertThat(sgm.path("line").asString()).isEqualTo("MOTOR");
    assertThat(sgm.path("openClaims").asLong()).isEqualTo(6);
    money(sgm, "caseOutstanding", "17500.50", "provisional", "6500.50", "assessed", "11000.00", "paid", "7000.00",
        "incurred", "24500.50");
    JsonNode sgp = j.path("rows").get(4);
    assertThat(sgp.path("line").asString()).isEqualTo("PROPERTY");
    assertThat(sgp.path("openClaims").asLong()).isEqualTo(1);
    money(sgp, "caseOutstanding", "4000.00", "provisional", "0.00", "assessed", "4000.00", "paid", "0.00", "incurred", "4000.00");
    JsonNode hk = j.path("rows").get(0);
    assertThat(hk.path("market").asString()).isEqualTo("HK");
    money(hk, "caseOutstanding", "10000.00", "provisional", "10000.00", "assessed", "0.00", "paid", "2000.00", "incurred", "12000.00");
    JsonNode idp = j.path("rows").get(1);
    assertThat(idp.path("market").asString()).isEqualTo("ID");
    assertThat(idp.path("line").asString()).isEqualTo("MOTOR");
    assertThat(idp.path("openClaims").asLong()).isZero();
    money(idp, "caseOutstanding", "0", "provisional", "0", "assessed", "0", "paid", "2500000", "incurred", "2500000");
    JsonNode idq = j.path("rows").get(2);
    assertThat(idq.path("line").asString()).isEqualTo("PROPERTY");
    assertThat(idq.path("openClaims").asLong()).isEqualTo(1);
    money(idq, "caseOutstanding", "15000000", "provisional", "15000000", "assessed", "0", "paid", "0", "incurred", "15000000");

    JsonNode totals = j.path("totalsByCurrency");
    assertThat(totals.size()).isEqualTo(3);
    JsonNode sgd = find(totals, "currency", "SGD");
    assertThat(sgd.path("openClaims").asLong()).isEqualTo(7);
    money(sgd, "caseOutstanding", "21500.50", "provisional", "6500.50", "assessed", "15000.00", "paid", "7000.00", "incurred", "28500.50");
    JsonNode hkd = find(totals, "currency", "HKD");
    assertThat(hkd.path("openClaims").asLong()).isEqualTo(1);
    money(hkd, "caseOutstanding", "10000.00", "provisional", "10000.00", "assessed", "0.00", "paid", "2000.00", "incurred", "12000.00");
    JsonNode idr = find(totals, "currency", "IDR");
    assertThat(idr.path("openClaims").asLong()).isEqualTo(1);
    money(idr, "caseOutstanding", "15000000", "provisional", "15000000", "assessed", "0", "paid", "2500000", "incurred", "17500000");

    JsonNode usd = j.path("indicativeUsdTotal");
    assertThat(usd.path("label").asString()).isEqualTo("indicative");
    assertThat(usd.path("note").asString()).containsIgnoringCase("indicative");
    money(usd, "caseOutstanding", "18120.37", "paid", "5591.00", "incurred", "23711.37");
    assertThat(usd.path("usdPerUnitRates").path("SGD").asString()).isEqualTo("0.74");
    assertThat(usd.path("usdPerUnitRates").path("HKD").asString()).isEqualTo("0.128");
    assertThat(usd.path("usdPerUnitRates").path("IDR").asString()).isEqualTo("0.000062");
    assertThat(usd.path("excludedCurrencies").size()).isZero();
  }

  // ---- workload ----

  @Test
  void workloadEveryNumber() throws Exception {
    JsonNode j = get("/api/v1/insights/workload", manager()).json();
    assertThat(j.path("asOf").asString()).isEqualTo("2026-06-15T11:59:00Z");
    JsonNode officers = j.path("officers");
    assertThat(officers.size()).isEqualTo(4);
    assertOfficer(find(officers, "officerName", "Priya"), 2, 0, 1, 7);
    assertOfficer(find(officers, "officerName", "Wei"), 3, 1, 0, 15);
    assertOfficer(find(officers, "officerName", "Hana"), 1, 0, 0, 1);
    assertOfficer(find(officers, "officerName", "Budi"), 1, 0, 0, 4);
    JsonNode team = j.path("team");
    assertThat(team.path("openClaims").asLong()).isEqualTo(9);
    assertThat(team.path("overdue").asLong()).isEqualTo(2);
    assertThat(team.path("waitingOnClaimant").asLong()).isEqualTo(1);
    assertThat(team.path("unassigned").asLong()).isEqualTo(2);
    JsonNode a = j.path("ageing");
    assertThat(a.path("days0to2").asLong()).isEqualTo(3);
    assertThat(a.path("days3to7").asLong()).isEqualTo(3);
    assertThat(a.path("days8to14").asLong()).isEqualTo(2);
    assertThat(a.path("days15plus").asLong()).isEqualTo(1);
  }

  private static void assertOfficer(JsonNode o, long open, long overdue, long waiting, long oldestDays) {
    assertThat(o.path("openClaims").asLong()).as(o.path("officerName").asString() + " open").isEqualTo(open);
    assertThat(o.path("overdue").asLong()).as("overdue").isEqualTo(overdue);
    assertThat(o.path("waitingOnClaimant").asLong()).as("waiting").isEqualTo(waiting);
    assertThat(o.path("oldestAgeDays").asLong()).as("oldest").isEqualTo(oldestDays);
  }

  /** Each boundary: 2d23h is in 0-2d, 3d in 3-7d, 7d in 3-7d, 8d in 8-14d, 14d in 8-14d, 15d in 15d+. */
  @Test
  void ageingBoundariesAreExact() throws Exception {
    jdbc.update("TRUNCATE claim_projection");
    String[] ages = {"2d", "2d23h", "3d", "7d", "7d23h", "8d", "14d", "14d23h", "15d"};
    for (String age : ages) {
      claim("SG", "MOTOR", "UNDER_REVIEW", PRIYA, "Priya", "SGD", "1.00", "PROVISIONAL", "0", age, 1L, null, null, "1m");
    }
    JsonNode a = get("/api/v1/insights/workload", sgOfficer()).json().path("ageing");
    assertThat(a.path("days0to2").asLong()).as("2d, 2d23h").isEqualTo(2);
    assertThat(a.path("days3to7").asLong()).as("3d, 7d, 7d23h").isEqualTo(3);
    assertThat(a.path("days8to14").asLong()).as("8d, 14d, 14d23h").isEqualTo(3);
    assertThat(a.path("days15plus").asLong()).as("15d").isEqualTo(1);
  }

  /** The clock moves: the same rows age, and pick-up/decision deadlines flip exactly one second after due. */
  @Test
  void overdueFlipsJustAfterTheDeadline() throws Exception {
    // c1 (SUBMITTED, unassigned): pickup due == T0, so overdue only strictly after.
    assertThat(get("/api/v1/insights/workload", sgOfficer()).json().path("team").path("overdue").asLong()).isEqualTo(2);
    CLOCK.set(T0.plusSeconds(1));
    assertThat(get("/api/v1/insights/workload", sgOfficer()).json().path("team").path("overdue").asLong()).isEqualTo(3);
  }

  // ---- performance ----

  @Test
  void performanceEveryNumber() throws Exception {
    JsonNode j = get("/api/v1/insights/performance?days=30", manager()).json();
    assertThat(j.path("days").asInt()).isEqualTo(30);
    perf(j.path("overall"), 5, 0.8, 6.09, 6.0, 129.6, 160.0);
    perf(find(j.path("byMarket"), "key", "SG"), 3, 0.6667, 6.14, 6.0, 192.0, 360.0);
    perf(find(j.path("byMarket"), "key", "HK"), 1, 1.0, 4.0, 4.0, 24.0, 48.0);
    perf(find(j.path("byMarket"), "key", "ID"), 1, 1.0, 8.0, 8.0, 48.0, 72.0);
    perf(find(j.path("byOfficer"), "name", "Priya"), 2, 0.5, 4.75, 3.0, 132.0, 360.0);
    JsonNode wei = find(j.path("byOfficer"), "name", "Wei");
    perf(wei, 1, 1.0, 8.0, 8.0, 312.0, null);
    assertThat(wei.path("avgHoursToSettlement").isNull()).as("no settlement: null, not 0").isTrue();
  }

  @Test
  void noDataIsNullNotZeroAndNeverDividesByZero() throws Exception {
    JsonNode id = find(get("/api/v1/insights/performance?days=1", manager()).json().path("byMarket"), "key", "ID");
    assertThat(id.path("decided").asLong()).isZero();
    for (String f : new String[] {"approvalRate", "avgHoursToFirstPickup", "medianHoursToFirstPickup",
        "avgHoursToDecision", "avgHoursToSettlement"}) {
      assertThat(id.path(f).isNull()).as(f).isTrue();
    }
    jdbc.update("TRUNCATE claim_projection");
    Resp empty = get("/api/v1/insights/performance", manager());
    assertThat(empty.status()).isEqualTo(200);
    assertThat(empty.json().path("overall").path("approvalRate").isNull()).isTrue();
    assertThat(empty.json().path("asOf").isNull()).isTrue();
  }

  @Test
  void daysIsBounded() throws Exception {
    for (String d : new String[] {"0", "366", "-5", "abc"}) {
      assertThat(get("/api/v1/insights/performance?days=" + d, manager()).status()).as(d).isEqualTo(400);
    }
    assertThat(get("/api/v1/insights/performance?days=1", manager()).status()).isEqualTo(200);
    assertThat(get("/api/v1/insights/performance?days=365", manager()).status()).isEqualTo(200);
  }

  private static void perf(JsonNode r, long decided, Double rate, Double avgPickup, Double medPickup, Double decision, Double settlement) {
    assertThat(r.path("decided").asLong()).as("decided").isEqualTo(decided);
    assertThat(r.path("approvalRate").asDouble()).as("approvalRate").isEqualTo(rate);
    assertThat(r.path("avgHoursToFirstPickup").asDouble()).as("avg pickup").isEqualTo(avgPickup);
    assertThat(r.path("medianHoursToFirstPickup").asDouble()).as("median pickup").isEqualTo(medPickup);
    assertThat(r.path("avgHoursToDecision").asDouble()).as("decision").isEqualTo(decision);
    if (settlement != null) assertThat(r.path("avgHoursToSettlement").asDouble()).as("settlement").isEqualTo(settlement);
  }

  // ---- scoping and auth ----

  @Test
  void sgOfficerSeesOnlySgAndAsOfOfThatScope() throws Exception {
    JsonNode j = get("/api/v1/insights/exposure", sgOfficer()).json();
    for (JsonNode row : j.path("rows")) assertThat(row.path("market").asString()).isEqualTo("SG");
    assertThat(j.path("totalsByCurrency").size()).isEqualTo(1);
    assertThat(j.path("asOf").asString()).isEqualTo("2026-06-15T11:55:00Z"); // SG newest is c2, T-5m, not HK's T-1m
    JsonNode w = get("/api/v1/insights/workload", sgOfficer()).json();
    assertThat(w.path("team").path("openClaims").asLong()).isEqualTo(7);
    assertThat(w.path("officers").size()).isEqualTo(2);
    assertThat(get("/api/v1/insights/performance", sgOfficer()).json().path("byMarket").size()).isEqualTo(1);
  }

  @Test
  void marketFilterCannotWidenScope() throws Exception {
    for (String path : new String[] {"exposure", "workload", "performance"}) {
      JsonNode j = get("/api/v1/insights/" + path + "?market=HK", sgOfficer()).json();
      assertThat(j.path("asOf").isNull()).as(path + " asOf").isTrue();
      if (path.equals("exposure")) assertThat(j.path("rows").size()).isZero();
      if (path.equals("workload")) assertThat(j.path("team").path("openClaims").asLong()).isZero();
      if (path.equals("performance")) assertThat(j.path("byMarket").size()).isZero();
    }
    JsonNode both = get("/api/v1/insights/exposure?market=SG&market=HK", sgOfficer()).json();
    assertThat(both.path("totalsByCurrency").size()).isEqualTo(1);
    // a manager can narrow to one market
    JsonNode hk = get("/api/v1/insights/exposure?market=HK", manager()).json();
    assertThat(hk.path("rows").size()).isEqualTo(1);
    assertThat(get("/api/v1/insights/exposure?market=sg", manager()).status()).isEqualTo(400);
  }

  /** Found by Schemathesis: both used to give 500 INTERNAL_ERROR. */
  @Test
  void anUnparseableQueryStringAndAMultipartContentTypeWithoutBoundaryAre400NotServerErrors() throws Exception {
    Resp q = get("/api/v1/insights/exposure?=4.8e%2B16&a=b", manager());
    assertThat(q.status()).as(q.body()).isEqualTo(400);
    assertThat(q.json().path("code").asString()).isEqualTo("MALFORMED_REQUEST");
    HttpResponse<String> m = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/insights/workload"))
        .header("Authorization", "Bearer " + manager()).header("Content-Type", "multipart/form-data").GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(m.statusCode()).as(m.body()).isEqualTo(400);
    assertThat(m.body()).doesNotContain("errorId");
  }

  @Test
  void managerSeesAllClaimantIs403AndNoTokenIs401() throws Exception {
    assertThat(get("/api/v1/insights/exposure", manager()).json().path("totalsByCurrency").size()).isEqualTo(3);
    for (String p : new String[] {"exposure", "workload", "performance"}) {
      Resp claimant = get("/api/v1/insights/" + p, TestTokens.token("CLAIMANT", "SG"));
      assertThat(claimant.status()).as(p).isEqualTo(403);
      assertThat(claimant.contentType()).startsWith("application/problem+json");
      assertThat(get("/api/v1/insights/" + p, null).status()).as(p).isEqualTo(401);
    }
    Resp noMarkets = get("/api/v1/insights/exposure", TestTokens.token("OFFICER"));
    assertThat(noMarkets.status()).isEqualTo(200);
    assertThat(noMarkets.json().path("rows").size()).isZero();
  }
}
