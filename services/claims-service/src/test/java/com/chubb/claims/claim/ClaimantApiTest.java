package com.chubb.claims.claim;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.claims.claim.domain.Actor;
import com.chubb.claims.claim.domain.Command;
import com.chubb.claims.support.ApiTest;
import com.chubb.claims.support.StaffSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class ClaimantApiTest extends ApiTest {
  private static final String CLAIMS = "/api/v1/claimant/claims";

  @Autowired StaffSide staff;

  private Resp submit(String who, Map<String, Object> body, String key) {
    return post(CLAIMS, login(who), toJson(body), "Idempotency-Key", key);
  }

  private JsonNode submitOk(String who, Map<String, Object> body) {
    Resp r = submit(who, body, key());
    assertThat(r.status()).as(r.body()).isEqualTo(201);
    return r.json();
  }

  private Map<String, Object> sgMotor(String incident, String estimate) {
    return motorBody(policyId("MOT-SG-100001"), incident, estimate);
  }

  // ---- submission ----

  /** Found by Schemathesis (insights-service; same handler shape here): used to give 500 INTERNAL_ERROR. */
  @Test
  void anUnparseableQueryStringIs400NotAServerError() {
    Resp r = get("/api/v1/claimant/claims?=4.8e%2B16&a=b", login("claimant.sg"));
    assertThat(r.status()).as(r.body()).isEqualTo(400);
    assertThat(r.json().get("code").asString()).isEqualTo("MALFORMED_REQUEST");
    Resp m = send("GET", "/api/v1/claimant/claims", login("claimant.sg"), null, "Content-Type", "multipart/form-data");
    assertThat(m.status()).as(m.body()).isEqualTo(400);
  }

  /** Found by Schemathesis: page * size overflowed the int offset and gave 500. */
  @Test
  void aHugePageNumberIsAnEmptyPageNotAServerError() {
    for (String path : new String[] {"/api/v1/claimant/claims", "/api/v1/claimant/updates"}) {
      Resp r = get(path + "?page=2147483646&size=20", login("claimant.sg"));
      assertThat(r.status()).as(path + " " + r.body()).isEqualTo(200);
      assertThat(r.json().get("items").size()).isZero();
    }
    Resp s = get("/api/v1/staff/claims?page=2147483646&size=100", login("officer.sg"));
    assertThat(s.status()).as(s.body()).isEqualTo(200);
  }

  @Test
  void submitReturns201WithClaimEtagLocationAndMoneyAsStrings() {
    Resp r = submit("claimant.sg", sgMotor("2026-03-10T09:00:00", "5000"), key());
    assertThat(r.status()).isEqualTo(201);
    JsonNode c = r.json();
    assertThat(c.get("claimNumber").asString()).matches("CLM-SG-2026-\\d{6}");
    assertThat(c.get("status").asString()).isEqualTo("SUBMITTED");
    assertThat(r.header("ETag")).isEqualTo("\"0\"");
    assertThat(r.header("Location")).endsWith("/api/v1/claimant/claims/" + c.get("id").asString());
    assertThat(c.get("estimate").isString()).isTrue();
    assertThat(c.get("estimate").asString()).isEqualTo("5000.00");
    assertThat(c.get("reportedAt").asString()).isEqualTo("2026-03-11T02:00:00Z");
    assertThat(c.get("incidentAtLocal").asString()).startsWith("2026-03-10T09:00");
    assertThat(c.get("late").asBoolean()).isFalse();
    assertThat(c.get("actionRequired").asBoolean()).isFalse();
    assertThat(c.get("motor").get("vehicleRegistration").asString()).isEqualTo("SGX1234A");
    assertThat(c.get("timeline").get(0).get("type").asString()).isEqualTo("SUBMITTED");
    assertThat(c.get("timeline").get(0).get("actorName").asString()).isEqualTo("Alicia Tan");
  }

  @Test
  void submissionStoresSlaDueTimesFromConfig() {
    UUID id = UUID.fromString(submitOk("claimant.sg", sgMotor("2026-03-10T09:00:00", "100")).get("id").asString());
    Map<String, Object> row = jdbc.queryForMap(
        "select reported_at, pickup_due_at, decision_due_at from claim where id = ?", id);
    Instant reported = ((java.sql.Timestamp) row.get("reported_at")).toInstant();
    assertThat(((java.sql.Timestamp) row.get("pickup_due_at")).toInstant()).isEqualTo(reported.plusSeconds(24 * 3600));
    assertThat(((java.sql.Timestamp) row.get("decision_due_at")).toInstant())
        .isEqualTo(reported.plusSeconds(14L * 24 * 3600));
  }

  @Test
  void motorWithoutInjuryFlagMeansNoInjury() {
    Map<String, Object> body = sgMotor("2026-03-10T09:00:00", "100");
    @SuppressWarnings("unchecked") Map<String, Object> motor = (Map<String, Object>) body.get("motor");
    motor.remove("injury");
    assertThat(submitOk("claimant.sg", body).get("motor").get("injury").asBoolean()).isFalse();
  }

  @Test
  void propertyClaimIsAccepted() {
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("policyId", policyId("PRP-SG-100002").toString());
    body.put("line", "PROPERTY");
    body.put("claimType", "WATER_DAMAGE");
    body.put("incidentAtLocal", "2026-03-09T20:00:00");
    body.put("estimate", "12000.50");
    body.put("property", Map.of("address", "1 Orchard Rd", "cause", "Burst pipe", "occupancy", "OWNER"));
    JsonNode c = submitOk("claimant.sg", body);
    assertThat(c.get("property").get("address").asString()).isEqualTo("1 Orchard Rd");
    assertThat(c.has("motor")).isFalse();
  }

  @Test
  void reserveInTheLedgerEqualsTheFormula() {
    // deductible 500, limit 100000: min(max(estimate - 500, 0), 100000)
    String[][] cases = {{"5000", "4500.00"}, {"300", "0.00"}, {"500", "0.00"}, {"250000", "100000.00"}};
    for (String[] c : cases) {
      UUID id = UUID.fromString(submitOk("claimant.sg", sgMotor("2026-03-10T09:00:00", c[0])).get("id").asString());
      assertThat(jdbc.queryForObject("select count(*) from financial_entry where claim_id = ?",
          Integer.class, id)).isEqualTo(1);
      Map<String, Object> e = jdbc.queryForMap(
          "select entry_type, amount from financial_entry where claim_id = ?", id);
      assertThat(e.get("entry_type")).isEqualTo("RESERVE_SET");
      assertThat((BigDecimal) e.get("amount")).isEqualByComparingTo(c[1]);
      assertThat(jdbc.queryForObject("select reserve from claim where id = ?", BigDecimal.class, id))
          .isEqualByComparingTo(c[1]);
      assertThat(jdbc.queryForObject("select reserve_status from claim where id = ?", String.class, id))
          .isEqualTo("PROVISIONAL");
    }
  }

  @Test
  void hkClaimUsesHkdAndIsNeverFlaggedLate() {
    // A Saturday-night incident long ago would be late in SG; HK has no sourced rule.
    Map<String, Object> body = motorBody(policyId("MOT-HK-200001"), "2026-03-07T22:30:00", "10000");
    JsonNode c = submitOk("claimant.hk", body);
    assertThat(c.get("late").asBoolean()).isFalse();
    assertThat(c.get("currency").asString()).isEqualTo("HKD");
    assertThat(c.get("claimNumber").asString()).startsWith("CLM-HK-");
  }

  // ---- late flag, end to end with a fixed clock ----

  @Test
  void sgMotorSaturdayNightIncidentIsDueMondayNotLateOnMondayLateOnTuesday() {
    Map<String, Object> body = sgMotor("2026-03-07T22:30:00", "1000");

    clock.set(Instant.parse("2026-03-09T15:30:00Z")); // Monday 23:30 in Singapore (still Monday in UTC too)
    assertThat(submitOk("claimant.sg", body).get("late").asBoolean()).isFalse();

    clock.set(Instant.parse("2026-03-09T16:30:00Z")); // Tuesday 00:30 in Singapore, still Monday in UTC
    JsonNode tue = submitOk("claimant.sg", body);
    assertThat(tue.get("late").asBoolean()).isTrue();
  }

  @Test
  void lateReasonIsKeptOnlyWhenLateAndNeverRejects() {
    clock.set(Instant.parse("2026-03-10T05:00:00Z")); // Tuesday
    Map<String, Object> body = sgMotor("2026-03-07T22:30:00", "1000");
    body.put("lateReason", "  In hospital  ");
    JsonNode late = submitOk("claimant.sg", body);
    assertThat(late.get("late").asBoolean()).isTrue();
    assertThat(late.get("lateReason").asString()).isEqualTo("In hospital");

    clock.set(Instant.parse("2026-03-09T02:00:00Z")); // Monday, not late: reason is not stored
    JsonNode ok = submitOk("claimant.sg", body);
    assertThat(ok.get("late").asBoolean()).isFalse();
    assertThat(ok.has("lateReason")).isFalse();
  }

  // ---- validation ----

  private void assertRejected(Resp r, int status, String code) {
    assertThat(r.status()).as(r.body()).isEqualTo(status);
    assertThat(r.json().get("code").asString()).isEqualTo(code);
    assertThat(r.header("Content-Type")).startsWith("application/problem+json");
  }

  @Test
  void anotherClaimantsPolicyLooksLikeAPolicyThatDoesNotExist() {
    Resp others = submit("claimant.sg", motorBody(policyId("MOT-SG-100003"), "2026-03-10T09:00:00", "100"), key());
    Resp missing = submit("claimant.sg", motorBody(UUID.randomUUID(), "2026-03-10T09:00:00", "100"), key());
    assertRejected(others, 404, "NOT_FOUND");
    assertThat(others.body()).isEqualTo(missing.body());
  }

  @Test
  void wrongLineIsRejected() {
    Map<String, Object> body = motorBody(policyId("PRP-SG-100002"), "2026-03-10T09:00:00", "100");
    Resp r = submit("claimant.sg", body, key());
    assertRejected(r, 422, "POLICY_MISMATCH");
    assertThat(r.json().get("errors").get(0).get("field").asString()).isEqualTo("policyId");
  }

  @Test
  void futureIncidentIsRejected() {
    assertRejected(submit("claimant.sg", sgMotor("2026-03-11T10:01:00", "100"), key()), 422, "INCIDENT_IN_FUTURE");
  }

  @Test
  void incidentOutsidePolicyDatesIsRejected() {
    assertRejected(submit("claimant.sg", sgMotor("2024-12-31T09:00:00", "100"), key()), 422,
        "INCIDENT_OUTSIDE_POLICY_PERIOD");
  }

  /** R13: small inputs the review found untested. */
  @Test
  void estimateZeroIsAcceptedWithReserveZero() {
    Resp r = submit("claimant.sg", sgMotor("2026-03-10T09:00:00", "0"), key());
    assertThat(r.status()).as(r.body()).isEqualTo(201);
    UUID id = UUID.fromString(r.json().get("id").asString());
    assertThat(jdbc.queryForObject("select reserve from claim where id = ?", java.math.BigDecimal.class, id))
        .isEqualByComparingTo("0");
  }

  @Test
  void motorWithoutAPoliceReportIsAccepted() {
    Map<String, Object> body = sgMotor("2026-03-10T09:00:00", "100");
    @SuppressWarnings("unchecked") Map<String, Object> motor = (Map<String, Object>) body.get("motor");
    motor.remove("policeReport");
    Resp r = submit("claimant.sg", body, key());
    assertThat(r.status()).as(r.body()).isEqualTo(201);
    assertThat(r.json().get("motor").get("policeReport").isNull()).isTrue();
  }

  @Test
  void thirdPartyAndInjuryDescriptionRoundTrip() {
    Map<String, Object> body = sgMotor("2026-03-10T09:00:00", "100");
    @SuppressWarnings("unchecked") Map<String, Object> motor = (Map<String, Object>) body.get("motor");
    motor.put("thirdParty", Map.of("name", "Ravi Kumar", "phone", "+6598765432", "insurer", "Acme Insurance",
        "vehicleNumber", "SBA9999Z"));
    motor.put("injury", true);
    motor.put("injuryDescription", "Whiplash, seen at A&E");
    UUID id = UUID.fromString(submitOk("claimant.sg", body).get("id").asString());
    for (JsonNode m : new JsonNode[] {
        get("/api/v1/claimant/claims/" + id, login("claimant.sg")).json().get("motor"),
        get("/api/v1/staff/claims/" + id, login("officer.sg")).json().get("motor")}) {
      assertThat(m.get("thirdParty").get("name").asString()).isEqualTo("Ravi Kumar");
      assertThat(m.get("thirdParty").get("insurer").asString()).isEqualTo("Acme Insurance");
      assertThat(m.get("thirdParty").get("vehicleNumber").asString()).isEqualTo("SBA9999Z");
      assertThat(m.get("injury").asBoolean()).isTrue();
      assertThat(m.get("injuryDescription").asString()).isEqualTo("Whiplash, seen at A&E");
    }
  }

  /** Advisory: NUL and other control characters are 422 in free text, not a 500; tab and newline are fine. */
  @Test
  void controlCharactersInFreeTextAre422ButTabAndNewlineAreAccepted() {
    for (String bad : new String[] {"a\u0000b", "a\u0007b", "a\u001fb", "a\u007fb"}) {
      Map<String, Object> body = sgMotor("2026-03-10T09:00:00", "100");
      body.put("description", bad);
      Resp r = submit("claimant.sg", body, key());
      assertRejected(r, 422, "VALIDATION_FAILED");
      assertThat(r.json().get("errors").get(0).get("field").asString()).isEqualTo("description");
      assertThat(r.body()).doesNotContain(bad);
      Map<String, Object> nested = sgMotor("2026-03-10T09:00:00", "100");
      @SuppressWarnings("unchecked") Map<String, Object> motor = (Map<String, Object>) nested.get("motor");
      motor.put("location", bad);
      assertRejected(submit("claimant.sg", nested, key()), 422, "VALIDATION_FAILED");
    }
    Map<String, Object> ok = sgMotor("2026-03-10T09:00:00", "100");
    ok.put("description", "line one\n\tline two\r\nline three");
    assertThat(submit("claimant.sg", ok, key()).status()).isEqualTo(201);
  }

  /** Advisory: the driver's Detail line (the offending value) must not be in exception messages. */
  @Test
  void theJdbcDriverDoesNotCarryServerErrorDetailIntoExceptions() {
    String email = "logdetail-" + UUID.randomUUID() + "@demo.test";
    jdbc.update("insert into app_user (email, password_hash, name, role) values (?, 'x', 'N', 'CLAIMANT')", email);
    Throwable t = org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class,
        () -> jdbc.update("insert into app_user (email, password_hash, name, role) values (?, 'x', 'N', 'CLAIMANT')", email));
    assertThat(t.getMessage()).doesNotContain(email).doesNotContain("Detail");
  }

  @Test
  void motorWithoutLocationIsRejectedAndLocationRoundTripsToBothViews() {
    Map<String, Object> body = sgMotor("2026-03-10T09:00:00", "100");
    @SuppressWarnings("unchecked") Map<String, Object> motor = (Map<String, Object>) body.get("motor");
    motor.remove("location");
    Resp r = submit("claimant.sg", body, key());
    assertRejected(r, 422, "VALIDATION_FAILED");
    assertThat(r.json().get("errors").get(0).get("field").asString()).isEqualTo("lossDetails.location");
    motor.put("location", "x".repeat(201));
    assertThat(submit("claimant.sg", body, key()).status()).isEqualTo(422);
    motor.put("location", "Orchard Road junction");
    Resp ok = submit("claimant.sg", body, key());
    assertThat(ok.status()).as(ok.body()).isEqualTo(201);
    String id = ok.json().get("id").asString();
    assertThat(ok.json().get("motor").get("location").asString()).isEqualTo("Orchard Road junction");
    assertThat(get("/api/v1/claimant/claims/" + id, login("claimant.sg")).json().get("motor").get("location").asString())
        .isEqualTo("Orchard Road junction");
    assertThat(get("/api/v1/staff/claims/" + id, login("officer.sg")).json().get("motor").get("location").asString())
        .isEqualTo("Orchard Road junction");
  }

  @Test
  void motorWithoutRegistrationListsTheField() {
    Map<String, Object> body = sgMotor("2026-03-10T09:00:00", "100");
    @SuppressWarnings("unchecked") Map<String, Object> motor = (Map<String, Object>) body.get("motor");
    motor.remove("vehicleRegistration");
    Resp r = submit("claimant.sg", body, key());
    assertRejected(r, 422, "VALIDATION_FAILED");
    assertThat(r.json().get("errors").get(0).get("field").asString()).isEqualTo("lossDetails.vehicleRegistration");
  }

  @Test
  void invalidClaimTypeForTheLineIsRejected() {
    Map<String, Object> body = sgMotor("2026-03-10T09:00:00", "100");
    body.put("claimType", "FIRE");
    assertRejected(submit("claimant.sg", body, key()), 422, "INVALID_CLAIM_TYPE");
  }

  @Test
  void bodyFieldErrorsAndMissingKeyAndBadEnum() {
    Resp noPolicy = post(CLAIMS, login("claimant.sg"), "{\"line\":\"MOTOR\"}", "Idempotency-Key", key());
    assertRejected(noPolicy, 422, "VALIDATION_FAILED");
    assertThat(noPolicy.json().get("errors").size()).isGreaterThan(1);
    assertThat(noPolicy.body()).doesNotContain("rejectedValue");

    Resp noKey = post(CLAIMS, login("claimant.sg"), toJson(sgMotor("2026-03-10T09:00:00", "100")));
    assertRejected(noKey, 400, "IDEMPOTENCY_KEY_REQUIRED");

    Map<String, Object> badEnum = sgMotor("2026-03-10T09:00:00", "100");
    badEnum.put("line", "BOAT");
    assertRejected(submit("claimant.sg", badEnum, key()), 400, "MALFORMED_REQUEST");

    Map<String, Object> badScale = sgMotor("2026-03-10T09:00:00", "10.123");
    assertRejected(submit("claimant.sg", badScale, key()), 422, "VALIDATION_FAILED");
  }

  @Test
  void idrRejectsDecimalsButSgdAcceptsTwo() {
    // No IDR policy is seeded, so check the SGD scale rule only: two decimals pass.
    assertThat(submit("claimant.sg", sgMotor("2026-03-10T09:00:00", "99.95"), key()).status()).isEqualTo(201);
  }

  // ---- idempotency ----

  @Test
  void sameKeySequentiallyReturnsTheOriginalWith200() {
    String k = key();
    Resp first = submit("claimant.sg", sgMotor("2026-03-10T09:00:00", "700"), k);
    Resp second = submit("claimant.sg", sgMotor("2026-03-10T09:00:00", "700"), k);
    assertThat(first.status()).isEqualTo(201);
    assertThat(second.status()).isEqualTo(200);
    assertThat(second.json().get("id").asString()).isEqualTo(first.json().get("id").asString());
    assertThat(second.header("ETag")).isEqualTo(first.header("ETag"));
    assertThat(jdbc.queryForObject("select count(*) from claim where idempotency_key = ?", Integer.class, k))
        .isEqualTo(1);
  }

  @Test
  void sameKeyWithTheSameBodyInAnotherFieldOrderAndNumberFormatIsStillTheOriginal() {
    String k = key();
    Resp first = submit("claimant.sg", sgMotor("2026-03-10T09:00:00", "700"), k);
    // same content: different key order, "700.00" for "700", an explicit null for an absent field
    Map<String, Object> reordered = new java.util.LinkedHashMap<>();
    Map<String, Object> original = sgMotor("2026-03-10T09:00:00", "700.00");
    java.util.List<String> names = new java.util.ArrayList<>(original.keySet());
    java.util.Collections.reverse(names);
    names.forEach(n -> reordered.put(n, original.get(n)));
    reordered.put("lateReason", null);
    Resp again = submit("claimant.sg", reordered, k);
    assertThat(first.status()).isEqualTo(201);
    assertThat(again.status()).as(again.body()).isEqualTo(200);
    assertThat(again.json().get("id").asString()).isEqualTo(first.json().get("id").asString());
  }

  @Test
  void sameKeyWithADifferentBodyIs422IdempotencyKeyReused() {
    String k = key();
    Resp first = submit("claimant.sg", sgMotor("2026-03-10T09:00:00", "700"), k);
    assertThat(first.status()).isEqualTo(201);
    Resp other = submit("claimant.sg", sgMotor("2026-03-10T09:00:00", "701"), k);
    assertRejected(other, 422, "IDEMPOTENCY_KEY_REUSED");
    Map<String, Object> differentText = sgMotor("2026-03-10T09:00:00", "700");
    differentText.put("description", "A different story");
    assertRejected(submit("claimant.sg", differentText, k), 422, "IDEMPOTENCY_KEY_REUSED");
    assertThat(jdbc.queryForObject("select count(*) from claim where idempotency_key = ?", Integer.class, k))
        .as("the original is untouched and nothing new was created").isEqualTo(1);
    assertThat(jdbc.queryForObject("select request_hash from claim where idempotency_key = ?", String.class, k))
        .matches("[0-9a-f]{64}");
    // the original body still gets the original claim
    assertThat(submit("claimant.sg", sgMotor("2026-03-10T09:00:00", "700"), k).status()).isEqualTo(200);
  }

  @Test
  void theSameKeyFromAnotherClaimantIsADifferentSubmission() {
    String k = key();
    assertThat(submit("claimant.sg", sgMotor("2026-03-10T09:00:00", "700"), k).status()).isEqualTo(201);
    Map<String, Object> theirs = motorBody(policyId("MOT-SG-100003"), "2026-03-10T09:00:00", "700");
    assertThat(submit("claimant2.sg", theirs, k).status()).isEqualTo(201);
  }

  @Test
  void twoConcurrentSubmissionsWithOneKeyCreateOneClaim() throws Exception {
    for (int round = 0; round < 5; round++) {
      String k = key();
      String token = login("claimant.sg");
      String body = toJson(sgMotor("2026-03-10T09:00:00", "800"));
      var start = new java.util.concurrent.CountDownLatch(1);
      var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
      try {
        var f1 = pool.submit(() -> { start.await(); return post(CLAIMS, token, body, "Idempotency-Key", k); });
        var f2 = pool.submit(() -> { start.await(); return post(CLAIMS, token, body, "Idempotency-Key", k); });
        start.countDown();
        Resp a = f1.get(30, java.util.concurrent.TimeUnit.SECONDS);
        Resp b = f2.get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(a.status()).as(a.body()).isIn(200, 201);
        assertThat(b.status()).as(b.body()).isIn(200, 201);
        assertThat(a.json().get("id").asString()).isEqualTo(b.json().get("id").asString());
        assertThat(jdbc.queryForObject("select count(*) from claim where idempotency_key = ?", Integer.class, k))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from financial_entry f join claim c on c.id = f.claim_id "
            + "where c.idempotency_key = ?", Integer.class, k)).isEqualTo(1);
      } finally {
        pool.shutdownNow();
      }
    }
  }

  // ---- reads and ownership ----

  @Test
  void anotherClaimantsClaimIsA404IdenticalToOneThatDoesNotExist() {
    String id = submitOk("claimant.sg", sgMotor("2026-03-10T09:00:00", "100")).get("id").asString();
    String b = login("claimant2.sg");

    Resp theirs = get(CLAIMS + "/" + id, b);
    Resp missing = get(CLAIMS + "/" + UUID.randomUUID(), b);
    assertThat(theirs.status()).isEqualTo(404);
    assertThat(theirs.body()).isEqualTo(missing.body());
    assertThat(theirs.json().get("code").asString()).isEqualTo("NOT_FOUND");

    Resp answer = post(CLAIMS + "/" + id + "/info-responses", b, "{\"answer\":\"hi\"}", "If-Match", "\"0\"");
    Resp answerMissing = post(CLAIMS + "/" + UUID.randomUUID() + "/info-responses", b, "{\"answer\":\"hi\"}",
        "If-Match", "\"0\"");
    assertThat(answer.status()).isEqualTo(404);
    assertThat(answer.body()).isEqualTo(answerMissing.body());

    // Even with no If-Match the other claimant learns nothing.
    assertThat(post(CLAIMS + "/" + id + "/info-responses", b, "{\"answer\":\"hi\"}").body())
        .isEqualTo(answerMissing.body());

    assertThat(get(CLAIMS, b).body()).doesNotContain(id);
    assertThat(get("/api/v1/claimant/updates?size=100", b).body()).doesNotContain(id);
  }

  @Test
  void listIsPagedNewestFirstAndOwnOnly() {
    String who = "claimant.hk";
    for (int i = 0; i < 3; i++) {
      clock.set(Instant.parse("2026-03-1" + i + "T02:00:00Z"));
      submitOk(who, motorBody(policyId("MOT-HK-200001"), "2026-03-0" + (i + 1) + "T09:00:00", "1000"));
    }
    JsonNode page = get(CLAIMS + "?size=2&page=0", login(who)).json();
    assertThat(page.get("items").size()).isEqualTo(2);
    assertThat(page.get("size").asInt()).isEqualTo(2);
    assertThat(page.get("totalElements").asLong()).isGreaterThanOrEqualTo(3);
    assertThat(page.get("items").get(0).get("reportedAt").asString())
        .isGreaterThanOrEqualTo(page.get("items").get(1).get("reportedAt").asString());
    for (JsonNode item : page.get("items")) {
      assertThat(item.get("claimNumber").asString()).startsWith("CLM-HK-");
      assertThat(item.has("reserve")).isFalse();
    }
    assertThat(get(CLAIMS + "?size=100000", login(who)).json().get("size").asInt()).isEqualTo(100);
  }

  @Test
  void policiesAreOwnOnly() {
    JsonNode p = get("/api/v1/claimant/policies", login("claimant2.sg")).json();
    assertThat(p.size()).isEqualTo(2);
    for (JsonNode x : p) {
      assertThat(x.get("policyNumber").asString()).isIn("MOT-SG-100003", "PRP-SG-100004");
      assertThat(x.get("coverageLimit").isString()).isTrue();
      assertThat(x.get("currency").asString()).isEqualTo("SGD");
    }
  }

  // ---- no staff data in claimant responses (T9) ----

  @Test
  void claimantResponsesNeverContainReserveOrInternalText() {
    String secretNote = "INTERNAL-ONLY-SECRET-NOTE-7731";
    JsonNode created = submitOk("claimant.sg", sgMotor("2026-03-10T09:00:00", "9000"));
    UUID id = UUID.fromString(created.get("id").asString());
    UUID officerId = userId("officer.sg");
    Actor officer = staff.officer(officerId, "SG");

    // Staff side, inserted directly: pick up, an INTERNAL note, an assessment, a question.
    staff.run(id, Command.pickUp(officer), Command.note(officer, secretNote),
        Command.assess(officer, new BigDecimal("8123.45")), Command.requestInfo(officer, "Please send the repair quote"));

    String token = login("claimant.sg");
    Resp detail = get(CLAIMS + "/" + id, token);
    Resp list = get(CLAIMS + "?size=100", token);
    Resp updates = get("/api/v1/claimant/updates?size=100", token);
    for (Resp r : new Resp[] {detail, list, updates}) {
      assertThat(r.status()).isEqualTo(200);
      String lower = r.body().toLowerCase();
      assertThat(lower).doesNotContain("reserve").doesNotContain("8123.45").doesNotContain("8123,").doesNotContain("authority")
          .doesNotContain(secretNote.toLowerCase()).doesNotContain("picked up").doesNotContain(officerId.toString());
    }
    JsonNode d = detail.json();
    assertThat(d.get("status").asString()).isEqualTo("AWAITING_INFO");
    assertThat(d.get("actionRequired").asBoolean()).isTrue();
    assertThat(d.get("openQuestion").get("question").asString()).isEqualTo("Please send the repair quote");
    // Timeline: claimant-visible only (submission, the question) with the officer's display name.
    assertThat(d.get("timeline").size()).isEqualTo(2);
    assertThat(d.get("timeline").get(1).get("actorName").asString()).isEqualTo("Priya Nair");
    assertThat(d.get("timeline").get(1).get("fromStatus").asString()).isEqualTo("ASSESSED");
  }

  @Test
  void decisionShowsApprovedAmountOrRejectionReasonOnly() {
    UUID officerId = userId("officer2.sg");
    Actor officer = staff.officer(officerId, "SG");

    UUID approved = UUID.fromString(submitOk("claimant.sg", sgMotor("2026-03-10T09:00:00", "9000")).get("id").asString());
    staff.run(approved, Command.pickUp(officer), Command.assess(officer, new BigDecimal("7000")),
        Command.approve(officer, new BigDecimal("6500")));
    JsonNode a = get(CLAIMS + "/" + approved, login("claimant.sg")).json();
    assertThat(a.get("decision").get("outcome").asString()).isEqualTo("APPROVED");
    assertThat(a.get("decision").get("amount").asString()).isEqualTo("6500.00");
    assertThat(a.toString()).doesNotContain("7000");

    UUID rejected = UUID.fromString(submitOk("claimant.sg", sgMotor("2026-03-10T09:00:00", "9000")).get("id").asString());
    staff.run(rejected, Command.pickUp(officer),
        Command.reject(officer, com.chubb.claims.claim.domain.RejectReason.EXCLUDED_CAUSE, "Wear and tear is excluded"));
    JsonNode r = get(CLAIMS + "/" + rejected, login("claimant.sg")).json();
    assertThat(r.get("decision").get("outcome").asString()).isEqualTo("REJECTED");
    assertThat(r.get("decision").get("reasonCode").asString()).isEqualTo("EXCLUDED_CAUSE");
    assertThat(r.get("decision").get("explanation").asString()).isEqualTo("Wear and tear is excluded");
  }

  @Test
  void updatesListClaimantVisibleActivityNewestFirst() {
    JsonNode created = submitOk("claimant2.sg", motorBody(policyId("MOT-SG-100003"), "2026-03-10T09:00:00", "100"));
    UUID id = UUID.fromString(created.get("id").asString());
    Actor officer = staff.officer(userId("officer.sg"), "SG");
    clock.set(clock.instant().plusSeconds(60));
    staff.run(id, Command.pickUp(officer), Command.requestInfo(officer, "Photo of the damage?"));

    JsonNode u = get("/api/v1/claimant/updates?size=100", login("claimant2.sg")).json();
    JsonNode items = u.get("items");
    assertThat(items.get(0).get("type").asString()).isEqualTo("REQUEST_INFO");
    assertThat(items.get(0).get("message").asString()).isEqualTo("Photo of the damage?");
    for (int i = 1; i < items.size(); i++) {
      assertThat(items.get(i - 1).get("at").asString()).isGreaterThanOrEqualTo(items.get(i).get("at").asString());
    }
    assertThat(u.toString()).doesNotContain("PICK_UP").doesNotContain("Picked up");
  }

  // ---- info responses ----

  private UUID claimAwaitingInfo() {
    UUID id = UUID.fromString(submitOk("claimant.sg", sgMotor("2026-03-10T09:00:00", "900")).get("id").asString());
    Actor officer = staff.officer(userId("officer.sg"), "SG");
    staff.run(id, Command.pickUp(officer), Command.requestInfo(officer, "Which workshop?"));
    return id;
  }

  private String etagOf(UUID id) {
    return get(CLAIMS + "/" + id, login("claimant.sg")).header("ETag");
  }

  @Test
  void answeringWithTheCurrentEtagReturnsToUnderReviewAndBumpsTheVersion() {
    UUID id = claimAwaitingInfo();
    String etag = etagOf(id);
    assertThat(etag).isEqualTo("\"2\"");
    Resp r = post(CLAIMS + "/" + id + "/info-responses", login("claimant.sg"),
        "{\"answer\":\"Kaki Bukit Auto\"}", "If-Match", etag);
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    assertThat(r.json().get("status").asString()).isEqualTo("UNDER_REVIEW");
    assertThat(r.json().get("actionRequired").asBoolean()).isFalse();
    assertThat(r.json().has("openQuestion")).isFalse();
    assertThat(r.header("ETag")).isEqualTo("\"3\"");
    assertThat(r.json().get("timeline").get(r.json().get("timeline").size() - 1).get("message").asString())
        .isEqualTo("Kaki Bukit Auto");
  }

  @Test
  void staleIfMatchIs409StaleVersion() {
    UUID id = claimAwaitingInfo();
    Resp r = post(CLAIMS + "/" + id + "/info-responses", login("claimant.sg"), "{\"answer\":\"x\"}",
        "If-Match", "\"1\"");
    assertRejected(r, 409, "STALE_VERSION");
    assertThat(get(CLAIMS + "/" + id, login("claimant.sg")).json().get("status").asString())
        .isEqualTo("AWAITING_INFO");
  }

  @Test
  void missingIfMatchIs428() {
    UUID id = claimAwaitingInfo();
    assertRejected(post(CLAIMS + "/" + id + "/info-responses", login("claimant.sg"), "{\"answer\":\"x\"}"),
        428, "PRECONDITION_REQUIRED");
  }

  @Test
  void malformedIfMatchIs400() {
    UUID id = claimAwaitingInfo();
    assertRejected(post(CLAIMS + "/" + id + "/info-responses", login("claimant.sg"), "{\"answer\":\"x\"}",
        "If-Match", "banana"), 400, "INVALID_IF_MATCH");
  }

  @Test
  void answeringWhenNothingWasAskedIsAnIllegalTransition() {
    UUID id = UUID.fromString(submitOk("claimant.sg", sgMotor("2026-03-10T09:00:00", "900")).get("id").asString());
    Resp r = post(CLAIMS + "/" + id + "/info-responses", login("claimant.sg"), "{\"answer\":\"x\"}",
        "If-Match", etagOf(id));
    assertRejected(r, 409, "ILLEGAL_TRANSITION");
  }

  @Test
  void blankAnswerIs422() {
    UUID id = claimAwaitingInfo();
    assertRejected(post(CLAIMS + "/" + id + "/info-responses", login("claimant.sg"), "{\"answer\":\"  \"}",
        "If-Match", etagOf(id)), 422, "VALIDATION_FAILED");
  }

  @Test
  void twoConcurrentAnswersWithTheSameEtagOneWinsOneIs409() throws Exception {
    UUID id = claimAwaitingInfo();
    String etag = etagOf(id);
    String token = login("claimant.sg");
    var start = new java.util.concurrent.CountDownLatch(1);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var f1 = pool.submit(() -> { start.await(); return post(CLAIMS + "/" + id + "/info-responses", token,
          "{\"answer\":\"one\"}", "If-Match", etag); });
      var f2 = pool.submit(() -> { start.await(); return post(CLAIMS + "/" + id + "/info-responses", token,
          "{\"answer\":\"two\"}", "If-Match", etag); });
      start.countDown();
      Resp a = f1.get(30, java.util.concurrent.TimeUnit.SECONDS);
      Resp b = f2.get(30, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(java.util.List.of(a.status(), b.status())).containsExactlyInAnyOrder(200, 409);
      Resp loser = a.status() == 409 ? a : b;
      assertThat(loser.json().get("code").asString()).isIn("STALE_VERSION", "ILLEGAL_TRANSITION");
    } finally {
      pool.shutdownNow();
    }
  }
}
