package com.chubb.claims.claim;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.claims.claim.domain.Command;
import com.chubb.claims.support.StaffApiTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

class StaffApiClaimsTest extends StaffApiTest {

  private static List<String> names(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asString()));
    return out;
  }

  private void assertLedger(JsonNode detail, String reserve, String reserveStatus, String paid, String outstanding,
      String incurred) {
    JsonNode l = detail.get("ledger");
    assertThat(l.get("reserve").isString()).isTrue();
    assertThat(l.get("reserve").asString()).isEqualTo(reserve);
    assertThat(l.get("reserveStatus").asString()).isEqualTo(reserveStatus);
    assertThat(l.get("paid").asString()).isEqualTo(paid);
    assertThat(l.get("outstanding").asString()).isEqualTo(outstanding);
    assertThat(l.get("incurred").asString()).isEqualTo(incurred);
  }

  @Test
  void fullJourneyChecksStatusLedgerAndDerivedFiguresAtEachStep() {
    UUID id = newClaim("5000");
    JsonNode d = staffGet("officer.sg", id);
    assertThat(d.get("status").asString()).isEqualTo("SUBMITTED");
    assertThat(d.get("version").asLong()).isZero();
    assertLedger(d, "4500.00", "PROVISIONAL", "0.00", "4500.00", "4500.00");
    assertThat(names(d.get("allowedActions"))).containsExactlyInAnyOrder("PICK_UP");
    assertThat(staffGet("manager.sg", id).get("allowedActions").size()).isEqualTo(2); // PICK_UP, ASSIGN

    // pick-up
    d = ok("officer.sg", id, "pick-up", null);
    assertThat(d.get("status").asString()).isEqualTo("UNDER_REVIEW");
    assertThat(d.get("assignedOfficer").get("name").asString()).isEqualTo("Priya Nair");
    assertThat(d.get("version").asLong()).isEqualTo(1);
    assertThat(d.get("deadlines").get("firstPickedUpAt")).isNotNull();
    assertThat(jdbc.queryForObject("select first_picked_up_at is not null from claim where id = ?", Boolean.class, id))
        .isTrue();
    assertThat(names(d.get("allowedActions"))).containsExactlyInAnyOrder("ADD_NOTE", "REQUEST_INFO", "ASSESS", "REJECT");
    assertLedger(d, "4500.00", "PROVISIONAL", "0.00", "4500.00", "4500.00");

    // request information
    d = ok("officer.sg", id, "info-requests", Map.of("question", "Please send the repair estimate"));
    assertThat(d.get("status").asString()).isEqualTo("AWAITING_INFO");
    assertThat(d.get("deadlines").get("waitingOnClaimant").asBoolean()).isTrue();
    assertThat(d.get("openQuestion").get("question").asString()).isEqualTo("Please send the repair estimate");
    JsonNode c = claimantGet(id);
    assertThat(c.get("actionRequired").asBoolean()).isTrue();
    assertThat(c.get("openQuestion").get("question").asString()).contains("repair estimate");

    // claimant answers
    Resp a = post(CLAIMANT_CLAIMS + "/" + id + "/info-responses", login("claimant.sg"), "{\"answer\":\"Attached\"}",
        "If-Match", current(id));
    assertThat(a.status()).as(a.body()).isEqualTo(200);
    d = staffGet("officer.sg", id);
    assertThat(d.get("status").asString()).isEqualTo("UNDER_REVIEW");
    assertThat(d.get("infoRequests").get(0).get("answer").asString()).isEqualTo("Attached");

    // assess
    d = ok("officer.sg", id, "assessment", Map.of("reserve", "4000"));
    assertThat(d.get("status").asString()).isEqualTo("ASSESSED");
    assertLedger(d, "4000.00", "ASSESSED", "0.00", "4000.00", "4000.00");

    // approve (manager)
    d = ok("manager.sg", id, "approval", Map.of("amount", "4000.00"));
    assertThat(d.get("status").asString()).isEqualTo("APPROVED");
    assertThat(d.get("ledger").get("approvedAmount").asString()).isEqualTo("4000.00");
    assertThat(d.get("deadlines").get("decidedAt")).isNotNull();
    assertThat(names(d.get("allowedActions"))).containsExactlyInAnyOrder("ASSIGN", "ADD_NOTE", "SETTLE");
    assertThat(claimantGet(id).get("decision").get("outcome").asString()).isEqualTo("APPROVED");

    // settle
    d = ok("officer.sg", id, "settlement", Map.of("amount", "4000.00"));
    assertThat(d.get("status").asString()).isEqualTo("SETTLED");
    assertLedger(d, "4000.00", "ASSESSED", "4000.00", "0.00", "4000.00");
    assertThat(d.get("deadlines").get("closedAt")).isNotNull();
    assertThat(d.get("allowedActions").size()).isZero();
    assertThat(d.get("version").asLong()).isEqualTo(6);

    Resp f = get(STAFF_CLAIMS + id + "/financials", login("officer.sg"));
    assertThat(f.status()).isEqualTo(200);
    JsonNode entries = f.json().get("entries");
    assertThat(entries.size()).isEqualTo(4);
    assertThat(entries.get(0).get("type").asString()).isEqualTo("RESERVE_SET");
    assertThat(entries.get(0).get("amount").asString()).isEqualTo("4500.00");
    assertThat(entries.get(3).get("type").asString()).isEqualTo("PAYMENT");
    assertThat(entries.get(3).get("amount").isString()).isTrue();
    assertThat(f.json().get("incurred").asString()).isEqualTo("4000.00");
    assertThat(jdbc.queryForObject("select closed_at is not null and decided_at is not null from claim where id = ?",
        Boolean.class, id)).isTrue();
  }

  @Test
  void rejectPath() {
    UUID id = newClaim("3000");
    ok("officer.sg", id, "pick-up", null);
    JsonNode d = ok("officer.sg", id, "rejection", Map.of("reason", "NOT_COVERED", "explanation", "Cause is excluded"));
    assertThat(d.get("status").asString()).isEqualTo("REJECTED");
    assertLedger(d, "0.00", "ASSESSED", "0.00", "0.00", "0.00");
    assertThat(d.get("rejection").get("reasonCode").asString()).isEqualTo("NOT_COVERED");
    assertThat(jdbc.queryForObject("select closed_at is not null and decided_at is not null from claim where id = ?",
        Boolean.class, id)).isTrue();
    JsonNode c = claimantGet(id);
    assertThat(c.get("decision").get("outcome").asString()).isEqualTo("REJECTED");
    assertThat(c.get("decision").get("explanation").asString()).isEqualTo("Cause is excluded");
    Resp again = cmd("officer.sg", id, "notes", Map.of("text", "x"), current(id));
    assertThat(again.status()).isEqualTo(409);
    assertThat(again.json().get("code").asString()).isEqualTo("ILLEGAL_TRANSITION");
  }

  @Test
  void assessThenRequestAgainReturnsToUnderReview() {
    UUID id = newClaim("5000");
    ok("officer.sg", id, "pick-up", null);
    assertThat(ok("officer.sg", id, "assessment", Map.of("reserve", "2000")).get("status").asString()).isEqualTo("ASSESSED");
    assertThat(ok("officer.sg", id, "info-requests", Map.of("question", "More photos?")).get("status").asString())
        .isEqualTo("AWAITING_INFO");
    Resp a = post(CLAIMANT_CLAIMS + "/" + id + "/info-responses", login("claimant.sg"), "{\"answer\":\"Here\"}",
        "If-Match", current(id));
    assertThat(a.status()).isEqualTo(200);
    assertThat(a.json().get("status").asString()).isEqualTo("UNDER_REVIEW");
  }

  @Test
  void twoConcurrentPickUpsWithTheSameIfMatchGiveExactlyOne200AndOneAssignee() throws Exception {
    UUID id = newClaim("1000");
    String etag = current(id);
    String t1 = login("officer.sg");
    String t2 = login("officer2.sg");
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Resp>> results = new ArrayList<>();
      for (String token : List.of(t1, t2)) {
        results.add(pool.submit(() -> {
          go.await();
          return post(STAFF_CLAIMS + id + "/pick-up", token, null, "If-Match", etag);
        }));
      }
      go.countDown();
      List<Integer> statuses = new ArrayList<>();
      Resp loser = null;
      for (Future<Resp> f : results) {
        Resp r = f.get();
        statuses.add(r.status());
        if (r.status() != 200) loser = r;
      }
      assertThat(statuses).containsExactlyInAnyOrder(200, 409);
      assertThat(loser.json().get("code").asString()).isIn("STALE_VERSION", "ILLEGAL_TRANSITION");
    } finally {
      pool.shutdownNow();
    }
    assertThat(jdbc.queryForObject("select count(*) from claim_activity where claim_id = ? and activity_type = 'PICK_UP'",
        Integer.class, id)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select assigned_officer_id from claim where id = ?", UUID.class, id)).isNotNull();
    assertThat(version(id)).isEqualTo(1);
  }

  static Stream<Arguments> commands() {
    return Stream.of(
        Arguments.of("pick-up", null),
        Arguments.of("assign", Map.of("officerId", "00000000-0000-0000-0000-000000000001")),
        Arguments.of("notes", Map.of("text", "n")),
        Arguments.of("info-requests", Map.of("question", "q")),
        Arguments.of("assessment", Map.of("reserve", "10")),
        Arguments.of("approval", Map.of("amount", "10")),
        Arguments.of("rejection", Map.of("reason", "OTHER", "explanation", "e")),
        Arguments.of("settlement", Map.of("amount", "10")));
  }

  @ParameterizedTest(name = "stale If-Match on {0} is 409 STALE_VERSION")
  @MethodSource("commands")
  void staleVersionIs409OnEveryCommand(String path, Object body) {
    UUID id = newClaim("1000");
    staffSide.run(id, Command.pickUp(officerSg())); // version 1
    Resp r = cmd("manager.sg", id, path, body, "\"0\"");
    assertThat(r.status()).as(r.body()).isEqualTo(409);
    assertThat(r.json().get("code").asString()).isEqualTo("STALE_VERSION");
    assertThat(version(id)).isEqualTo(1);
  }

  @ParameterizedTest(name = "missing If-Match on {0} is 428")
  @MethodSource("commands")
  void missingIfMatchIs428OnEveryCommand(String path, Object body) {
    UUID id = newClaim("1000");
    Resp r = cmd("manager.sg", id, path, body, null);
    assertThat(r.status()).as(r.body()).isEqualTo(428);
    assertThat(r.json().get("code").asString()).isEqualTo("PRECONDITION_REQUIRED");
  }

  @Test
  void officerLimitBlocksApprovalAboveItButAllowsTheManager() {
    UUID id = newClaim("30000");
    ok("officer.sg", id, "pick-up", null);
    ok("officer.sg", id, "assessment", Map.of("reserve", "20000"));
    Resp over = cmd("officer.sg", id, "approval", Map.of("amount", "10000.01"), current(id));
    assertThat(over.status()).isEqualTo(403);
    assertThat(over.json().get("code").asString()).isEqualTo("AUTHORITY_EXCEEDED");
    assertThat(staffGet("officer.sg", id).get("status").asString()).isEqualTo("ASSESSED");
    Resp mgr = cmd("manager.sg", id, "approval", Map.of("amount", "10000.01"), current(id));
    assertThat(mgr.status()).as(mgr.body()).isEqualTo(200);
    assertThat(mgr.json().get("ledger").get("approvedAmount").asString()).isEqualTo("10000.01");

    UUID second = newClaim("30000");
    ok("officer.sg", second, "pick-up", null);
    ok("officer.sg", second, "assessment", Map.of("reserve", "20000"));
    assertThat(cmd("officer.sg", second, "approval", Map.of("amount", "10000.00"), current(second)).status())
        .isEqualTo(200);
  }

  @Test
  void officerCannotHandleOrBeAssignedTheirOwnPolicyClaim() {
    UUID id = newClaim("1000");
    jdbc.update("update claim set claimant_id = ?, policy_id = ? where id = ?", userId("officer2.sg"), policyId("MOT-SG-100005"), id);
    Resp pick = cmd("officer2.sg", id, "pick-up", null, current(id));
    assertThat(pick.status()).isEqualTo(403);
    assertThat(pick.json().get("code").asString()).isEqualTo("CONFLICT_OF_INTEREST");
    Resp assign = cmd("manager.sg", id, "assign", Map.of("officerId", userId("officer2.sg").toString()), current(id));
    assertThat(assign.status()).isEqualTo(403);
    assertThat(assign.json().get("code").asString()).isEqualTo("CONFLICT_OF_INTEREST");
    // Staff reads apply the same rule: detail, financials and the documents list refuse the policyholder
    for (String path : new String[] {"", "/financials", "/documents"}) {
      Resp read = get(STAFF_CLAIMS + id + path, login("officer2.sg"));
      assertThat(read.status()).as("GET " + path + " -> " + read.body()).isEqualTo(403);
      assertThat(read.json().get("code").asString()).isEqualTo("CONFLICT_OF_INTEREST");
    }
    // other staff of the market are not affected
    assertThat(get(STAFF_CLAIMS + id, login("officer.sg")).status()).isEqualTo(200);
    assertThat(get(STAFF_CLAIMS + id + "/documents", login("officer.sg")).status()).isEqualTo(200);
  }

  @Test
  void illegalTransitionIs409() {
    UUID id = newClaim("1000");
    ok("officer.sg", id, "pick-up", null);
    Resp r = cmd("officer.sg", id, "approval", Map.of("amount", "10"), current(id));
    assertThat(r.status()).isEqualTo(409);
    assertThat(r.json().get("code").asString()).isEqualTo("ILLEGAL_TRANSITION");
    assertThat(cmd("manager.sg", id, "settlement", Map.of("amount", "10"), current(id)).json().get("code").asString())
        .isEqualTo("ILLEGAL_TRANSITION");
  }

  @Test
  void invalidAmountIs422AndAssignToWrongMarketOfficerIs422() {
    UUID id = newClaim("1000");
    ok("officer.sg", id, "pick-up", null);
    Resp bad = cmd("officer.sg", id, "assessment", Map.of("reserve", "-1"), current(id));
    assertThat(bad.status()).isEqualTo(422);
    assertThat(bad.json().get("code").asString()).isEqualTo("INVALID_AMOUNT");
    Resp hk = cmd("manager.sg", id, "assign", Map.of("officerId", userId("officer.hk").toString()), current(id));
    assertThat(hk.status()).isEqualTo(422);
    assertThat(hk.json().get("code").asString()).isEqualTo("INVALID_ASSIGNEE");
  }

  @Test
  void managerAssignChangesOfficerAndOfficersListIsMarketScoped() {
    UUID id = newClaim("1000");
    JsonNode d = ok("manager.sg", id, "assign", Map.of("officerId", userId("officer2.sg").toString()));
    assertThat(d.get("assignedOfficer").get("name").asString()).isEqualTo("Wei Ming Ong");
    assertThat(d.get("status").asString()).isEqualTo("UNDER_REVIEW");

    List<String> sg = new ArrayList<>();
    get("/api/v1/staff/officers", login("officer.sg")).json().forEach(o -> sg.add(o.get("name").asString()));
    assertThat(sg).contains("Priya Nair", "Wei Ming Ong", "Rachel Goh", "Daniel Foo").doesNotContain("Mei Ling Wong");
    String body = get("/api/v1/staff/officers", login("officer.sg")).body();
    assertThat(body).doesNotContain("email").doesNotContain("authority").doesNotContain("demo.test");
    List<String> hk = new ArrayList<>();
    get("/api/v1/staff/officers", login("officer.hk")).json().forEach(o -> hk.add(o.get("name").asString()));
    assertThat(hk).containsExactlyInAnyOrder("Mei Ling Wong", "Daniel Foo");
  }

  @Test
  void internalNotesNeverReachTheClaimantButStaffSeeThem() {
    UUID id = newClaim("1000");
    ok("officer.sg", id, "pick-up", null);
    ok("officer.sg", id, "notes", Map.of("text", "TOP-SECRET-INTERNAL-NOTE"));
    ok("officer.sg", id, "info-requests", Map.of("question", "Visible question"));

    Resp detail = get(CLAIMANT_CLAIMS + "/" + id, login("claimant.sg"));
    Resp updates = get("/api/v1/claimant/updates", login("claimant.sg"));
    assertThat(detail.body()).doesNotContain("TOP-SECRET").contains("Visible question");
    assertThat(updates.body()).doesNotContain("TOP-SECRET").contains("Visible question");
    for (String forbidden : List.of("reserve", "internal", "INTERNAL", "authority")) {
      assertThat(detail.body()).doesNotContain(forbidden);
    }
    JsonNode staff = staffGet("officer.sg", id);
    boolean seen = false;
    for (JsonNode e : staff.get("timeline")) {
      if (e.get("message").asString().contains("TOP-SECRET")) {
        seen = true;
        assertThat(e.get("visibility").asString()).isEqualTo("INTERNAL");
      }
    }
    assertThat(seen).isTrue();
    // decisions and settlement are claimant-visible; assessment is not
    postAnswer(id);
    ok("officer.sg", id, "assessment", Map.of("reserve", "700"));
    ok("officer.sg", id, "approval", Map.of("amount", "700"));
    ok("officer.sg", id, "settlement", Map.of("amount", "700"));
    String after = get(CLAIMANT_CLAIMS + "/" + id, login("claimant.sg")).body();
    assertThat(after).contains("\"Approved\"").contains("\"Settled\"").doesNotContain("\"Assessed\"");
  }

  private void postAnswer(UUID id) {
    Resp a = post(CLAIMANT_CLAIMS + "/" + id + "/info-responses", login("claimant.sg"), "{\"answer\":\"done\"}",
        "If-Match", current(id));
    assertThat(a.status()).as(a.body()).isEqualTo(200);
  }

  @Test
  void queuesAreMarketScopedOrderedByPickupDueAndFlagged() {
    UUID older = newClaim("1000");
    jdbc.update("update claim set pickup_due_at = pickup_due_at - interval '3 days' where id = ?", older);
    UUID newer = newClaim("1000");
    jdbc.update("update claim set pickup_due_at = pickup_due_at + interval '3 days' where id = ?", newer);
    // every page: other tests in the same database leave unassigned claims behind
    List<JsonNode> items = new ArrayList<>();
    JsonNode page;
    int pageNo = 0;
    do {
      page = get("/api/v1/staff/claims?queue=unassigned&size=100&page=" + pageNo++, login("officer.sg")).json();
      page.get("items").forEach(items::add);
    } while (page.get("items").size() == 100);
    List<String> ids = new ArrayList<>();
    items.forEach(i -> ids.add(i.get("id").asString()));
    assertThat(ids.indexOf(older.toString())).isGreaterThanOrEqualTo(0)
        .isLessThan(ids.indexOf(newer.toString()));
    for (JsonNode i : items) {
      if (i.get("id").asString().equals(older.toString())) {
        assertThat(i.get("overdue").asBoolean()).isTrue();
        assertThat(i.get("waitingOnClaimant").asBoolean()).isFalse();
        assertThat(i.get("late").isBoolean()).isTrue();
      }
      if (i.get("id").asString().equals(newer.toString())) assertThat(i.get("overdue").asBoolean()).isFalse();
    }
    assertThat(page.toString()).doesNotContain("\"market\":\"HK\"");
    ok("officer.sg", older, "pick-up", null);
    ok("officer.sg", older, "info-requests", Map.of("question", "q"));
    JsonNode mine = get("/api/v1/staff/claims?queue=mine&status=AWAITING_INFO", login("officer.sg")).json();
    assertThat(mine.get("items").get(0).get("waitingOnClaimant").asBoolean()).isTrue();
    assertThat(get("/api/v1/staff/claims?queue=mine", login("officer2.sg")).json().get("items").toString())
        .doesNotContain(older.toString());
    assertThat(get("/api/v1/staff/claims?queue=all&size=100", login("officer.hk")).json().get("items").toString())
        .doesNotContain(older.toString());
    assertThat(get("/api/v1/staff/claims?queue=bogus", login("officer.sg")).status()).isEqualTo(400);
  }

  @Test
  void otherMarketAndUnknownIdsGiveIdentical404() {
    UUID id = newClaim("1000");
    Resp other = get(STAFF_CLAIMS + id, login("officer.hk"));
    Resp unknown = get(STAFF_CLAIMS + UUID.randomUUID(), login("officer.hk"));
    assertThat(other.status()).isEqualTo(404);
    assertThat(other.body()).isEqualTo(unknown.body());
    Resp cmdOther = cmd("officer.hk", id, "pick-up", null, current(id));
    Resp cmdUnknown = cmd("officer.hk", UUID.randomUUID(), "pick-up", null, "\"0\"");
    assertThat(cmdOther.status()).isEqualTo(404);
    assertThat(cmdOther.body()).isEqualTo(cmdUnknown.body());
    assertThat(get(STAFF_CLAIMS + id + "/financials", login("officer.hk")).status()).isEqualTo(404);
  }

  // ---- LIFECYCLE order: scope, access, If-Match, transition table, preconditions, amount, authority ----

  @Test
  void accessIsCheckedBeforeIfMatch() {
    UUID id = newClaim("1000");
    // an officer of the market who is not on the claim sees the access result, not 428 or 409
    Resp noHeader = cmd("officer2.sg", id, "notes", Map.of("text", "x"), null);
    assertThat(noHeader.status()).isEqualTo(404);
    assertThat(noHeader.json().get("code").asString()).isEqualTo("NOT_FOUND");
    Resp stale = cmd("officer2.sg", id, "notes", Map.of("text", "x"), "\"99\"");
    assertThat(stale.status()).isEqualTo(404);
    // the policyholder gets 403 before any If-Match talk
    jdbc.update("update claim set claimant_id = ?, policy_id = ? where id = ?", userId("officer2.sg"), policyId("MOT-SG-100005"), id);
    assertThat(cmd("officer2.sg", id, "pick-up", null, null).status()).isEqualTo(403);
    // a staff member with access is asked for If-Match
    assertThat(cmd("manager.sg", id, "pick-up", null, null).status()).isEqualTo(428);
  }

  @Test
  void ifMatchIsCheckedBeforeTheTransitionTable() {
    UUID id = newClaim("1000");
    ok("officer.sg", id, "pick-up", null);
    // PICK_UP is a blank cell in UNDER_REVIEW, but a stale tag is reported first
    assertThat(cmd("officer.sg", id, "pick-up", null, "\"0\"").json().get("code").asString()).isEqualTo("STALE_VERSION");
    assertThat(cmd("officer.sg", id, "pick-up", null, null).json().get("code").asString())
        .isEqualTo("PRECONDITION_REQUIRED");
  }

  @Test
  void aBlankCellIsAlwaysIllegalTransition() {
    UUID id = newClaim("1000");
    ok("officer.sg", id, "pick-up", null);
    // PICK_UP on an UNDER_REVIEW claim (the same officer: nothing else is wrong with the request)
    assertThat(cmd("officer.sg", id, "pick-up", null, current(id)).json().get("code").asString())
        .isEqualTo("ILLEGAL_TRANSITION");
    // ANSWER_INFO on UNDER_REVIEW
    Resp answer = post(CLAIMANT_CLAIMS + "/" + id + "/info-responses", login("claimant.sg"),
        toJson(Map.of("answer", "x")), "If-Match", current(id));
    assertThat(answer.status()).isEqualTo(409);
    assertThat(answer.json().get("code").asString()).isEqualTo("ILLEGAL_TRANSITION");
    // ASSIGN with a bad target on a SETTLED claim
    ok("officer.sg", id, "assessment", Map.of("reserve", "800"));
    ok("manager.sg", id, "approval", Map.of("amount", "800"));
    ok("officer.sg", id, "settlement", Map.of("amount", "800"));
    Resp badTarget = cmd("manager.sg", id, "assign", Map.of("officerId", userId("officer.hk").toString()), current(id));
    assertThat(badTarget.status()).isEqualTo(409);
    assertThat(badTarget.json().get("code").asString()).isEqualTo("ILLEGAL_TRANSITION");
    // an invalid amount on a claim that cannot be assessed any more
    Resp amount = cmd("officer.sg", id, "assessment", Map.of("reserve", "-1"), current(id));
    assertThat(amount.json().get("code").asString()).isEqualTo("ILLEGAL_TRANSITION");
  }

  @Test
  void reassigningAnApprovedClaimBumpsTheVersionByOne() {
    UUID id = newClaim("3000");
    ok("officer.sg", id, "pick-up", null);
    ok("officer.sg", id, "assessment", Map.of("reserve", "2000"));
    ok("officer.sg", id, "approval", Map.of("amount", "2000"));
    long before = version(id);
    JsonNode d = ok("manager.sg", id, "assign", Map.of("officerId", userId("officer2.sg").toString()));
    assertThat(d.get("status").asString()).isEqualTo("APPROVED");
    assertThat(d.get("version").asLong()).isEqualTo(before + 1);
    assertThat(version(id)).isEqualTo(before + 1);
    assertThat(d.get("assignedOfficer").get("name").asString()).isEqualTo("Wei Ming Ong");
  }

  @Test
  void assessFourThousandApproveThreeAndAHalfThousandThenSettle() {
    UUID id = newClaim("5000");
    ok("officer.sg", id, "pick-up", null);
    ok("officer.sg", id, "assessment", Map.of("reserve", "4000"));
    JsonNode d = ok("officer.sg", id, "approval", Map.of("amount", "3500"));
    // The claim status after approval is APPROVED; it is the reserve status that stays ASSESSED.
    assertThat(d.get("status").asString()).isEqualTo("APPROVED");
    assertLedger(d, "3500.00", "ASSESSED", "0.00", "3500.00", "3500.00");
    assertThat(d.get("ledger").get("approvedAmount").asString()).isEqualTo("3500.00");
    List<String> ledger = jdbc.queryForList(
        "select entry_type || ':' || amount from financial_entry where claim_id = ? order by id", String.class, id);
    assertThat(ledger).containsExactly("RESERVE_SET:4500.00", "RESERVE_SET:4000.00", "RESERVE_SET:3500.00");
    assertThat(jdbc.queryForObject("select reserve from claim where id = ?", java.math.BigDecimal.class, id))
        .isEqualByComparingTo("3500");

    d = ok("officer.sg", id, "settlement", Map.of("amount", "3500"));
    assertThat(d.get("status").asString()).isEqualTo("SETTLED");
    assertLedger(d, "3500.00", "ASSESSED", "3500.00", "0.00", "3500.00");
    assertThat(jdbc.queryForObject("select paid from claim where id = ?", java.math.BigDecimal.class, id))
        .isEqualByComparingTo("3500");
  }

  @Test
  void approvalLimitIsPerMarketInTheMarketsCurrency() {
    String hash = jdbc.queryForObject("select password_hash from app_user where email = 'officer.sg@demo.test'", String.class);
    jdbc.update("insert into app_user (email, password_hash, name, role) values ('multi.officer@demo.test', ?, 'Multi Officer', 'OFFICER')", hash);
    jdbc.update("insert into user_market (user_id, market_code, authority_limit) "
        + "select id, 'SG', 10000 from app_user where email = 'multi.officer@demo.test'");
    jdbc.update("insert into user_market (user_id, market_code, authority_limit) "
        + "select id, 'ID', 200000000 from app_user where email = 'multi.officer@demo.test'");

    UUID sg = newClaim("30000");
    ok("multi.officer", sg, "pick-up", null);
    ok("multi.officer", sg, "assessment", Map.of("reserve", "20000"));
    Resp over = cmd("multi.officer", sg, "approval", Map.of("amount", "10000.01"), current(sg));
    assertThat(over.status()).as(over.body()).isEqualTo(403);
    assertThat(over.json().get("code").asString()).isEqualTo("AUTHORITY_EXCEEDED");
    assertThat(cmd("multi.officer", sg, "approval", Map.of("amount", "10000.00"), current(sg)).status()).isEqualTo(200);

    Resp created = post(CLAIMANT_CLAIMS, login("claimant.id"),
        toJson(motorBody(policyId("MOT-ID-400001"), "2026-03-10T09:00:00", "180000000")), "Idempotency-Key", key());
    assertThat(created.status()).as(created.body()).isEqualTo(201);
    UUID idr = UUID.fromString(created.json().get("id").asString());
    ok("multi.officer", idr, "pick-up", null);
    ok("multi.officer", idr, "assessment", Map.of("reserve", "160000000"));
    Resp fine = cmd("multi.officer", idr, "approval", Map.of("amount", "150000000"), current(idr));
    assertThat(fine.status()).as(fine.body()).isEqualTo(200);
    assertThat(fine.json().get("ledger").get("approvedAmount").asString()).isEqualTo("150000000");
  }

  /** Advisory: a manager with no user_market row for the market has no authority; a row with null is unlimited. */
  @Test
  void aManagerWithoutAMarketRowHasNoAuthorityButANullLimitRowIsUnlimited() {
    String hash = jdbc.queryForObject("select password_hash from app_user where email = 'officer.sg@demo.test'", String.class);
    for (String who : new String[] {"unlimited.manager", "removed.manager"}) {
      jdbc.update("insert into app_user (email, password_hash, name, role) values (?, ?, 'M', 'MANAGER')", who + "@demo.test", hash);
      jdbc.update("insert into user_market (user_id, market_code, authority_limit) "
          + "select id, 'SG', null from app_user where email = ?", who + "@demo.test");
    }
    UUID a = newClaim("30000");
    ok("officer.sg", a, "pick-up", null);
    ok("officer.sg", a, "assessment", Map.of("reserve", "20000"));
    UUID b = newClaim("30000");
    ok("officer.sg", b, "pick-up", null);
    ok("officer.sg", b, "assessment", Map.of("reserve", "20000"));

    login("removed.manager"); // the token still names SG
    jdbc.update("delete from user_market where user_id = ?", userId("removed.manager"));
    Resp gone = cmd("removed.manager", b, "approval", Map.of("amount", "25000"), current(b));
    assertThat(gone.status()).as(gone.body()).isEqualTo(403);
    assertThat(gone.json().get("code").asString()).isEqualTo("AUTHORITY_EXCEEDED");

    Resp fine = cmd("unlimited.manager", a, "approval", Map.of("amount", "25000"), current(a));
    assertThat(fine.status()).as(fine.body()).isEqualTo(200);
  }

  /** Advisory: the staff list excludes the caller's own claims on every queue. */
  @Test
  void staffListsExcludeTheCallersOwnClaimsOnEveryQueue() {
    UUID own = newClaim("1000");
    jdbc.update("update claim set claimant_id = ?, assigned_officer_id = ?, policy_id = ? where id = ?",
        userId("officer2.sg"), userId("officer2.sg"), policyId("MOT-SG-100005"), own);
    UUID other = newClaim("1000");
    // newest first, so both would be on the first page of every queue: absence can only be the exclusion
    jdbc.update("update claim set updated_at = now() + interval '1 day' where id in (?, ?)", own, other);
    for (String queue : new String[] {"unassigned", "mine", "all"}) {
      String body = get("/api/v1/staff/claims?queue=" + queue + "&size=100", login("officer2.sg")).body();
      assertThat(body).as(queue).doesNotContain(own.toString());
    }
    jdbc.update("update claim set assigned_officer_id = ? where id = ?", userId("officer2.sg"), other);
    assertThat(get("/api/v1/staff/claims?queue=mine&size=100", login("officer2.sg")).body()).contains(other.toString())
        .doesNotContain(own.toString());
    assertThat(get("/api/v1/staff/claims?queue=all&size=100", login("officer2.sg")).body()).contains(other.toString())
        .doesNotContain(own.toString());
    // the exclusion is the caller's own claims only: another officer's Mine queue is not touched
    jdbc.update("update claim set assigned_officer_id = ? where id = ?", userId("officer.sg"), other);
    assertThat(get("/api/v1/staff/claims?queue=mine&size=100", login("officer.sg")).body()).contains(other.toString());
  }

  @Test
  void aNulInAStaffNoteIs422NotA500() {
    UUID id = newClaim("100");
    ok("officer.sg", id, "pick-up", null);
    Resp note = cmd("officer.sg", id, "notes", Map.of("text", "x\u0000y"), current(id));
    assertThat(note.status()).as(note.body()).isEqualTo(422);
    assertThat(note.json().get("code").asString()).isEqualTo("VALIDATION_FAILED");
  }
}
