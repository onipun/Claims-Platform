package com.chubb.claims.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real HTTP against the running app through the real security filter chain. Tokens come from the
 * real login endpoint, or are signed here when a test needs a forged or expired one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfra.class)
public abstract class ApiTest {
  public static final String PASSWORD = "Password1!";
  protected static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

  @LocalServerPort protected int port;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected MutableClock clock;

  public record Resp(int status, String body, java.net.http.HttpHeaders headers) {
    public JsonNode json() {
      return JSON.readTree(body);
    }

    public String header(String name) {
      return headers.firstValue(name).orElse(null);
    }
  }

  @BeforeEach
  void resetClock() {
    clock.set(TestInfra.DEFAULT_NOW);
  }

  protected Resp send(String method, String path, String token, String body, String... headers) {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (token != null) b.header("Authorization", "Bearer " + token);
    if (body != null) b.header("Content-Type", "application/json");
    for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
    b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    try {
      HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
      return new Resp(r.statusCode(), r.body(), r.headers());
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  protected Resp get(String path, String token) { return send("GET", path, token, null); }

  protected Resp post(String path, String token, String body, String... headers) {
    return send("POST", path, token, body, headers);
  }

  /** Real login; tokens are cached for their hour. */
  protected String login(String shortEmail) {
    return TOKENS.computeIfAbsent(port + ":" + shortEmail, k -> {
      String e = shortEmail;
      Resp r = send("POST", "/api/v1/auth/login", null,
          "{\"email\":\"" + e + "@demo.test\",\"password\":\"" + PASSWORD + "\"}");
      if (r.status() != 200) throw new IllegalStateException("login failed for " + e + ": " + r.status());
      return r.json().get("accessToken").asString();
    });
  }

  protected UUID userId(String shortEmail) {
    return jdbc.queryForObject("select id from app_user where email = ?", UUID.class, shortEmail + "@demo.test");
  }

  protected UUID policyId(String policyNumber) {
    return jdbc.queryForObject("select id from policy where policy_number = ?", UUID.class, policyNumber);
  }

  // ---- hand-made tokens ----

  public static RSAKey newKey(String kid) {
    try {
      return new RSAKeyGenerator(2048).keyID(kid).generate();
    } catch (com.nimbusds.jose.JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String sign(RSAKey key, String kid, UUID sub, String role, List<String> markets, Instant exp) {
    try {
      Instant iat = exp.isBefore(Instant.now()) ? exp.minusSeconds(3600) : Instant.now();
      JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("claims-service").subject(sub.toString())
          .issueTime(Date.from(iat)).expirationTime(Date.from(exp))
          .claim("role", role).claim("markets", markets).claim("name", "Forged").build();
      SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (com.nimbusds.jose.JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  protected static Map<String, Object> motorBody(UUID policyId, String incident, String estimate) {
    Map<String, Object> m = new HashMap<>();
    m.put("policyId", policyId.toString());
    m.put("line", "MOTOR");
    m.put("claimType", "OWN_DAMAGE");
    m.put("incidentAtLocal", incident);
    m.put("estimate", estimate);
    m.put("description", "Rear-ended at a junction");
    m.put("motor", new HashMap<>(Map.of("vehicleRegistration", "SGX1234A", "driverName", "Alicia Tan",
        "injury", false, "policeReport", "PR-1", "location", "Orchard Road junction")));
    return m;
  }

  protected static String toJson(Object o) {
    return JSON.writeValueAsString(o);
  }

  protected static String key() {
    return UUID.randomUUID().toString();
  }
}
