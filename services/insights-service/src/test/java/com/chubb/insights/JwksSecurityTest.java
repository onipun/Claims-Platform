package com.chubb.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Insights verifies tokens against a JWKS it fetches over HTTP. The "claims-service" here is a
 * local stub that publishes only the public key, which is all insights is ever given.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
class JwksSecurityTest {
  private static final RSAKey ISSUER_KEY = key("kid-1");
  private static final RSAKey OTHER_KEY = key("kid-1"); // claims the same key id, different key material
  private static final AtomicInteger JWKS_FETCHES = new AtomicInteger();
  private static final HttpServer JWKS_STUB = startStub();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @LocalServerPort int port;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        () -> "http://localhost:" + JWKS_STUB.getAddress().getPort() + "/jwks.json");
  }

  @AfterAll
  static void stop() {
    JWKS_STUB.stop(0);
  }

  private static RSAKey key(String kid) {
    try {
      return new RSAKeyGenerator(2048).keyID(kid).generate();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private static HttpServer startStub() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      s.createContext("/jwks.json", ex -> {
        JWKS_FETCHES.incrementAndGet();
        byte[] body = new JWKSet(ISSUER_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
      });
      s.start();
      return s;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String token(RSAKey signWith, String role, Instant exp) throws JOSEException {
    JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("claims-service").subject(UUID.randomUUID().toString())
        .issueTime(Date.from(Instant.now().minusSeconds(5))).expirationTime(Date.from(exp))
        .claim("role", role).claim("markets", List.of("SG")).claim("name", "Test").build();
    SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signWith.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(signWith));
    return jwt.serialize();
  }

  private record Resp(int status, String body, String contentType) {}

  private Resp get(String path, String token) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (token != null) b.header("Authorization", "Bearer " + token);
    HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new Resp(r.statusCode(), r.body(), r.headers().firstValue("Content-Type").orElse(""));
  }

  private String code(Resp r) {
    return JSON.readTree(r.body()).get("code").asString();
  }

  @Test
  void healthIsPublicAndDoesNotNeedTheJwks() throws Exception {
    // No Kafka broker and no claims-service exist in this test: health is still UP and names neither.
    Resp r = get("/actuator/health", null);
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body()).contains("\"UP\"").doesNotContain("kafka").doesNotContain("claims");
    assertThat(get("/actuator/health/readiness", null).status()).isEqualTo(200);
  }

  @Test
  void staffTokenSignedByTheJwksKeyIsAccepted() throws Exception {
    for (String role : List.of("OFFICER", "MANAGER")) {
      Resp r = get("/api/v1/insights/x", token(ISSUER_KEY, role, Instant.now().plusSeconds(600)));
      // Past security: nothing is mapped at /x, so the application answers 404 as problem+json.
      assertThat(r.status()).as(role).isEqualTo(404);
      assertThat(code(r)).isEqualTo("NOT_FOUND");
    }
    assertThat(JWKS_FETCHES.get()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void tokenSignedByAnotherKeyIs401EvenWithTheSameKeyId() throws Exception {
    Resp r = get("/api/v1/insights/x", token(OTHER_KEY, "MANAGER", Instant.now().plusSeconds(600)));
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.contentType()).startsWith("application/problem+json");
    assertThat(code(r)).isEqualTo("UNAUTHENTICATED");
  }

  @Test
  void expiredTokenIs401() throws Exception {
    assertThat(get("/api/v1/insights/x", token(ISSUER_KEY, "OFFICER", Instant.now().minusSeconds(600))).status())
        .isEqualTo(401);
  }

  @Test
  void claimantIs403() throws Exception {
    Resp r = get("/api/v1/insights/x", token(ISSUER_KEY, "CLAIMANT", Instant.now().plusSeconds(600)));
    assertThat(r.status()).isEqualTo(403);
    assertThat(r.contentType()).startsWith("application/problem+json");
    assertThat(code(r)).isEqualTo("FORBIDDEN");
  }

  @Test
  void missingTokenAndUnknownRoleAreRefused() throws Exception {
    assertThat(get("/api/v1/insights/x", null).status()).isEqualTo(401);
    assertThat(get("/api/v1/insights/x", token(ISSUER_KEY, "ADMIN", Instant.now().plusSeconds(600))).status())
        .isEqualTo(403);
  }
}
