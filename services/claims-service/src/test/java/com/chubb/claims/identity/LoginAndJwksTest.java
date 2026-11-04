package com.chubb.claims.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.claims.support.ApiTest;
import com.nimbusds.jwt.SignedJWT;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class LoginAndJwksTest extends ApiTest {

  private Resp loginRaw(String email, String password) {
    return post("/api/v1/auth/login", null, toJson(java.util.Map.of("email", email, "password", password)));
  }

  @Test
  void loginIssuesAnRs256TokenWithTheDocumentedClaims() throws Exception {
    Resp r = loginRaw("officer.hk@demo.test", PASSWORD);
    assertThat(r.status()).isEqualTo(200);
    SignedJWT jwt = SignedJWT.parse(r.json().get("accessToken").asString());
    assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
    assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(userId("officer.hk").toString());
    assertThat(jwt.getJWTClaimsSet().getStringClaim("role")).isEqualTo("OFFICER");
    assertThat(jwt.getJWTClaimsSet().getStringListClaim("markets")).containsExactly("HK");
    assertThat(jwt.getJWTClaimsSet().getStringClaim("name")).isEqualTo("Mei Ling Wong");
    long lifetime = jwt.getJWTClaimsSet().getExpirationTime().getTime()
        - jwt.getJWTClaimsSet().getIssueTime().getTime();
    assertThat(lifetime).isEqualTo(3_600_000L);
    assertThat(r.json().get("user").has("passwordHash")).isFalse();
  }

  @Test
  void unknownUserAndWrongPasswordAreIndistinguishable() {
    Resp unknown = loginRaw("nobody@demo.test", PASSWORD);
    Resp wrong = loginRaw("claimant.sg@demo.test", "Wrong-password1");
    assertThat(unknown.status()).isEqualTo(401);
    assertThat(wrong.status()).isEqualTo(401);
    assertThat(unknown.body()).isEqualTo(wrong.body());
    assertThat(unknown.json().get("code").asString()).isEqualTo("INVALID_CREDENTIALS");
    assertThat(unknown.header("Content-Type")).startsWith("application/problem+json");
  }

  @Test
  void blankLoginFieldsAreFieldErrors() {
    Resp r = loginRaw("", "");
    assertThat(r.status()).isEqualTo(422);
    assertThat(r.json().get("code").asString()).isEqualTo("VALIDATION_FAILED");
    assertThat(r.json().get("errors").size()).isEqualTo(2);
  }

  @Test
  void jwksIsPublicAndHasNoPrivateParts() {
    Resp r = get("/api/v1/.well-known/jwks.json", null);
    assertThat(r.status()).isEqualTo(200);
    JsonNode key = r.json().get("keys").get(0);
    assertThat(key.get("kty").asString()).isEqualTo("RSA");
    assertThat(key.get("alg").asString()).isEqualTo("RS256");
    for (String priv : List.of("d", "p", "q", "dp", "dq", "qi")) assertThat(key.has(priv)).isFalse();
  }

  @Test
  void malformedAndUnknownRequestsGetProblemJsonWithoutInternals() {
    Resp bad = post("/api/v1/auth/login", null, "{not json");
    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.json().get("code").asString()).isEqualTo("MALFORMED_REQUEST");
    Resp missing = get("/api/v1/nope", login("claimant.sg"));
    assertThat(missing.status()).isEqualTo(404);
    assertThat(missing.json().get("code").asString()).isEqualTo("NOT_FOUND");
    Resp method = send("DELETE", "/api/v1/claimant/policies", login("claimant.sg"), null);
    assertThat(method.status()).isEqualTo(405);
    assertThat(method.header("Content-Type")).startsWith("application/problem+json");
  }
}
