package com.chubb.claims.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.claims.support.StaffApiTest;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/** T1 and T17: every endpoint, every kind of caller, through the real filter chain. */
class AuthorizationMatrixTest extends StaffApiTest {
  /** Placeholders replaced by a real claim and a real uploaded document, so "right role" can tell 404 from success. */
  private static final String CLAIM = "{claim}";
  private static final String DOC = "{doc}";
  private static final String SIGNED_BY_OTHER_KEY = "forged";
  private static final String EXPIRED = "expired";

  @Autowired JwtKeyStore keys;

  record Endpoint(String method, String path, String body, String rightUser, String wrongUser) {
    @Override public String toString() { return method + " " + path; }
  }

  static final List<Endpoint> ENDPOINTS = List.of(
      new Endpoint("GET", "/api/v1/claimant/policies", null, "claimant.sg", "officer.sg"),
      new Endpoint("POST", "/api/v1/claimant/claims", "{}", "claimant.sg", "officer.sg"),
      new Endpoint("GET", "/api/v1/claimant/claims", null, "claimant.sg", "manager.sg"),
      new Endpoint("GET", "/api/v1/claimant/claims/" + CLAIM, null, "claimant.sg", "officer.sg"),
      new Endpoint("POST", "/api/v1/claimant/claims/" + CLAIM + "/info-responses", "{\"answer\":\"x\"}",
          "claimant.sg", "officer2.sg"),
      new Endpoint("GET", "/api/v1/claimant/updates", null, "claimant.sg", "manager.apac"),
      new Endpoint("GET", "/api/v1/staff/officers", null, "officer.sg", "claimant.sg"),
      new Endpoint("GET", "/api/v1/staff/claims/" + CLAIM, null, "manager.sg", "claimant.sg"),
      new Endpoint("GET", "/api/v1/staff/claims/" + CLAIM + "/financials", null, "officer.sg", "claimant.sg"),
      new Endpoint("POST", "/api/v1/staff/claims/" + CLAIM + "/pick-up", null, "officer.sg", "claimant.sg"),
      new Endpoint("GET", "/api/v1/claimant/claims/" + CLAIM + "/documents", null, "claimant.sg", "officer.sg"),
      new Endpoint("GET", "/api/v1/claimant/claims/" + CLAIM + "/documents/" + DOC + "/content", null,
          "claimant.sg", "manager.sg"),
      new Endpoint("GET", "/api/v1/staff/claims/" + CLAIM + "/documents/" + DOC + "/content", null,
          "officer.sg", "claimant.sg"),
      new Endpoint("POST", "/api/v1/staff/claims/" + CLAIM + "/documents/" + DOC + "/review",
          "{\"decision\":\"ACCEPTED\"}", "manager.sg", "claimant.sg"), // an unassigned officer is hidden (404)
      new Endpoint("GET", "/api/v1/staff/claims", null, "officer.sg", "claimant.sg"),
      new Endpoint("GET", "/api/v1/staff/claims", null, "manager.sg", "claimant.hk"));

  static Stream<Arguments> endpoints() {
    return ENDPOINTS.stream().map(Arguments::of);
  }

  private String forgedToken(String shortEmail, String kind) {
    UUID sub = userId(shortEmail);
    String role = shortEmail.startsWith("claimant") ? "CLAIMANT" : shortEmail.startsWith("officer") ? "OFFICER" : "MANAGER";
    if (EXPIRED.equals(kind)) {
      // Signed with the REAL key, so only the expiry is wrong.
      return sign(keys.signingKey(), keys.keyId(), sub, role, List.of("SG"), Instant.now().minusSeconds(600));
    }
    RSAKey other = newKey(keys.keyId()); // even claims to be the real key id
    return sign(other, keys.keyId(), sub, role, List.of("SG"), Instant.now().plusSeconds(600));
  }

  private UUID realClaim;
  private String realDoc;

  /** One real claim with one uploaded document, shared by every case in this class. */
  private void ensureFixture() {
    if (realClaim != null) return;
    realClaim = newClaim("1000");
    Resp up = uploadPng(realClaim);
    realDoc = up.json().get("id").asString();
  }

  private Resp call(Endpoint e, String token) {
    ensureFixture();
    String path = e.path().replace(CLAIM, realClaim.toString()).replace(DOC, realDoc);
    return send(e.method(), path, token, e.body());
  }

  private void assertProblem(Resp r, int status, String code) {
    assertThat(r.status()).isEqualTo(status);
    assertThat(r.header("Content-Type")).startsWith("application/problem+json");
    assertThat(r.json().get("code").asString()).isEqualTo(code);
    assertThat(r.body()).doesNotContain("Exception").doesNotContain("at com.");
  }

  @ParameterizedTest(name = "no token: {0}")
  @MethodSource("endpoints")
  void noTokenIs401(Endpoint e) {
    assertProblem(call(e, null), 401, "UNAUTHENTICATED");
  }

  @ParameterizedTest(name = "wrong role: {0}")
  @MethodSource("endpoints")
  void wrongRoleIs403(Endpoint e) {
    assertProblem(call(e, login(e.wrongUser())), 403, "FORBIDDEN");
  }

  @ParameterizedTest(name = "right role passes security: {0}")
  @MethodSource("endpoints")
  void rightRoleIsNotRefusedBySecurity(Endpoint e) {
    Resp r = call(e, login(e.rightUser()));
    assertThat(r.status()).as(r.body()).isNotIn(401, 403);
    assertThat(r.status()).isLessThan(500);
    // the resource exists, so a 404 would mean the route or the fixture is wrong, not that security passed
    assertThat(r.status()).as("resource exists: " + e + " " + r.body()).isNotEqualTo(404);
  }

  @ParameterizedTest(name = "token signed by another key: {0}")
  @MethodSource("endpoints")
  void forgedTokenIs401(Endpoint e) {
    assertProblem(call(e, forgedToken(e.rightUser(), SIGNED_BY_OTHER_KEY)), 401, "UNAUTHENTICATED");
  }

  @ParameterizedTest(name = "expired token: {0}")
  @MethodSource("endpoints")
  void expiredTokenIs401(Endpoint e) {
    assertProblem(call(e, forgedToken(e.rightUser(), EXPIRED)), 401, "UNAUTHENTICATED");
  }

  @Test
  void garbageTokenGivesTheSame401() {
    Resp a = get("/api/v1/claimant/policies", "not.a.jwt");
    Resp b = get("/api/v1/claimant/policies", null);
    assertThat(a.body()).isEqualTo(b.body());
    assertThat(a.status()).isEqualTo(401);
  }

  @Test
  void publicEndpointsNeedNoToken() {
    assertThat(get("/api/v1/.well-known/jwks.json", null).status()).isEqualTo(200);
    assertThat(get("/actuator/health", null).status()).isEqualTo(200);
    assertThat(get("/v3/api-docs", null).status()).isEqualTo(200);
  }
}
