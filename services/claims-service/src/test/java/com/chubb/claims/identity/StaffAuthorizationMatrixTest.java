package com.chubb.claims.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.claims.claim.domain.Command;
import com.chubb.claims.support.StaffApiTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T10, T17, T19 and trap 6 for every staff endpoint: no token, claimant, officer of the same market
 * (assigned, and not assigned), officer of another market, manager, APAC manager. Each case gets a
 * fresh claim in the state the endpoint needs, and the exact status is asserted.
 */
class StaffAuthorizationMatrixTest extends StaffApiTest {
    enum State { SUBMITTED, UNDER_REVIEW, ASSESSED, APPROVED }

  /** who -> expected status; "-" is no token. */
  record Row(String name, String method, State state, String path, Function<StaffApiTest, Object> body,
      int claimant, int officerAssigned, int officerUnassigned, int officerOtherMarket, int manager, int apac) {
    @Override public String toString() { return name; }
  }

  private static Row cmd(String path, State s, Function<StaffApiTest, Object> body, int assignedOfficer, int unassignedOfficer,
      int manager) {
    return new Row("POST " + path, "POST", s, "/" + path, body, 403, assignedOfficer, unassignedOfficer, 404, manager, manager);
  }

  private static Row read(String path) {
    return new Row("GET " + path, "GET", State.UNDER_REVIEW, path, t -> null, 403, 200, 200, 404, 200, 200);
  }

  static Stream<Arguments> rows() {
    return Stream.of(
        cmd("pick-up", State.SUBMITTED, t -> null, 200, 200, 200),
        cmd("assign", State.UNDER_REVIEW, t -> null /* replaced below */, 404, 404, 200),
        cmd("notes", State.UNDER_REVIEW, t -> Map.of("text", "n"), 200, 404, 200),
        cmd("info-requests", State.UNDER_REVIEW, t -> Map.of("question", "q"), 200, 404, 200),
        cmd("assessment", State.UNDER_REVIEW, t -> Map.of("reserve", "100"), 200, 404, 200),
        cmd("approval", State.ASSESSED, t -> Map.of("amount", "100"), 200, 404, 200),
        cmd("rejection", State.UNDER_REVIEW, t -> Map.of("reason", "OTHER", "explanation", "e"), 200, 404, 200),
        cmd("settlement", State.APPROVED, t -> Map.of("amount", "100.00"), 200, 404, 200),
        read(""), read("/financials"), read("/documents"),
        new Row("GET documents/{doc}/content", "GET", State.UNDER_REVIEW, "/documents/DOC/content", t -> null,
            403, 200, 200, 404, 200, 200),
        new Row("POST documents/{doc}/review", "POST", State.UNDER_REVIEW, "/documents/DOC/review",
            t -> Map.of("decision", "ACCEPTED"), 403, 200, 404, 404, 200, 200)
    ).flatMap(r -> Stream.of("none", "claimant.sg", "officer.sg", "officer2.sg", "officer.hk", "manager.sg", "manager.apac")
        .map(who -> Arguments.of(r, who)));
  }

  @ParameterizedTest(name = "{0} as {1}")
  @MethodSource("rows")
  void exactStatusForEveryCaller(Row row, String who) {
    UUID id = newClaim("5000");
    if (row.state() != State.SUBMITTED) staffSide.run(id, Command.pickUp(officerSg()));
    if (row.state() == State.ASSESSED || row.state() == State.APPROVED) {
      staffSide.run(id, Command.assess(officerSg(), new BigDecimal("100.00")));
    }
    if (row.state() == State.APPROVED) staffSide.run(id, Command.approve(managerSg(), new BigDecimal("100.00")));

    String path = "/api/v1/staff/claims/" + id + row.path();
    if (path.contains("/DOC/")) {
      path = path.replace("DOC", uploadPng(id).json().get("id").asString());
    }
    Object body = row.name().equals("POST assign")
        ? Map.of("officerId", userId("officer2.sg").toString()) : row.body().apply(this);
    // The "assigned officer" is officer.sg; officer2.sg is in the same market but not assigned.
    int expected = switch (who) {
      case "none" -> 401;
      case "claimant.sg" -> row.claimant();
      case "officer.sg" -> row.officerAssigned();
      case "officer2.sg" -> row.officerUnassigned();
      case "officer.hk" -> row.officerOtherMarket();
      case "manager.sg" -> row.manager();
      default -> row.apac();
    };
    String token = who.equals("none") ? null : login(who);
    String[] headers = row.method().equals("POST") ? new String[] {"If-Match", current(id)} : new String[0];
    Resp r = send(row.method(), path, token, body == null ? (row.method().equals("POST") ? "{}" : null) : toJson(body), headers);
    assertThat(r.status()).as(row + " as " + who + ": " + r.body()).isEqualTo(expected);
    if (expected == 404) {
      Resp unknown = send(row.method(), path.replace(id.toString(), UUID.randomUUID().toString()), token,
          body == null ? (row.method().equals("POST") ? "{}" : null) : toJson(body), headers);
      if (!path.contains("/documents/")) assertThat(r.body()).isEqualTo(unknown.body());
    }
  }

  /** The officers list and the queues are scoped by token, not by id, so they have their own cases. */
  @org.junit.jupiter.api.Test
  void listEndpointsAllowStaffRefuseClaimantsAndNeverCrossMarkets() {
    for (String path : List.of("/api/v1/staff/claims", "/api/v1/staff/officers")) {
      assertThat(get(path, null).status()).isEqualTo(401);
      assertThat(get(path, login("claimant.sg")).status()).isEqualTo(403);
      for (String who : List.of("officer.sg", "officer2.sg", "officer.hk", "manager.sg", "manager.apac")) {
        assertThat(get(path, login(who)).status()).as(path + " " + who).isEqualTo(200);
      }
    }
    UUID id = newClaim("1000");
    assertThat(get("/api/v1/staff/claims?queue=all&size=100", login("officer.hk")).body()).doesNotContain(id.toString());
    assertThat(get("/api/v1/staff/claims?queue=all&size=100", login("manager.apac")).body()).contains(id.toString());
  }
}
