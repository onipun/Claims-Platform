package com.chubb.claims.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.claims.claim.domain.Actor;
import com.chubb.claims.claim.domain.Role;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Helpers for tests that drive the staff API: claims come from the real claimant endpoint. */
public abstract class StaffApiTest extends ApiTest {
  protected static final String CLAIMANT_CLAIMS = "/api/v1/claimant/claims";
  protected static final String STAFF_CLAIMS = "/api/v1/staff/claims/";

  @Autowired protected StaffSide staffSide;

  protected UUID newClaim(String estimate) {
    return newClaimBy("claimant.sg", estimate);
  }

  protected UUID newClaimBy(String claimant, String estimate) {
    Resp r = post(CLAIMANT_CLAIMS, login(claimant),
        toJson(motorBody(policyId("MOT-SG-100001"), "2026-03-10T09:00:00", estimate)), "Idempotency-Key", key());
    assertThat(r.status()).as(r.body()).isEqualTo(201);
    return UUID.fromString(r.json().get("id").asString());
  }

  protected Actor officerSg() {
    return new Actor(userId("officer.sg"), Role.OFFICER, Set.of("SG"), new BigDecimal("10000"));
  }

  protected Actor managerSg() {
    return new Actor(userId("manager.sg"), Role.MANAGER, Set.of("SG"), (BigDecimal) null);
  }

  protected long version(UUID id) {
    return jdbc.queryForObject("select version from claim where id = ?", Long.class, id);
  }

  protected String current(UUID id) {
    return "\"" + version(id) + "\"";
  }

  protected Resp cmd(String who, UUID id, String path, Object body, String ifMatch) {
    String[] headers = ifMatch == null ? new String[0] : new String[] {"If-Match", ifMatch};
    return post(STAFF_CLAIMS + id + "/" + path, login(who), body == null ? "{}" : toJson(body), headers);
  }

  /** Uses the claim's current version as If-Match and expects success. */
  protected JsonNode ok(String who, UUID id, String path, Object body) {
    Resp r = cmd(who, id, path, body, current(id));
    assertThat(r.status()).as(path + " -> " + r.body()).isEqualTo(200);
    assertThat(r.header("ETag")).isEqualTo("\"" + r.json().get("version").asLong() + "\"");
    return r.json();
  }

  protected JsonNode staffGet(String who, UUID id) {
    Resp r = get(STAFF_CLAIMS + id, login(who));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    return r.json();
  }

  protected JsonNode claimantGet(UUID id) {
    Resp r = get(CLAIMANT_CLAIMS + "/" + id, login("claimant.sg"));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    return r.json();
  }

  /** A real 1x1 PNG, uploaded through the claimant endpoint as POLICE_REPORT. */
  protected Resp uploadPng(UUID claim) {
    byte[] png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
    String b = "----m" + UUID.randomUUID();
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"documentType\"\r\n\r\nPOLICE_REPORT\r\n"
          + "--" + b + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"p.png\"\r\n"
          + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
      out.write(png);
      out.write(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
      HttpResponse<String> r = HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + CLAIMANT_CLAIMS + "/" + claim + "/documents"))
              .header("Authorization", "Bearer " + login("claimant.sg"))
              .header("Content-Type", "multipart/form-data; boundary=" + b)
              .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
      return new Resp(r.statusCode(), r.body(), r.headers());
    } catch (java.io.IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }
}
