package com.chubb.claims;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.claims.support.ApiTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * D10: the committed spec is the contract. Fails when the code-first spec drifts from
 * contracts/openapi/claims-service.json. To accept a deliberate change:
 * {@code mvn -pl services/claims-service test -Dtest=OpenApiContractTest -Dcontract.update=true}.
 */
class OpenApiContractTest extends ApiTest {
  static final Path COMMITTED = Path.of("..", "..", "contracts", "openapi", "claims-service.json");

  @Test
  void committedSpecMatchesTheRunningApplication() throws IOException {
    Resp r = get("/v3/api-docs", null);
    assertThat(r.status()).isEqualTo(200);
    String actual = normalise(r.json());

    if (Boolean.getBoolean("contract.update")) {
      Files.createDirectories(COMMITTED.getParent());
      Files.writeString(COMMITTED, actual, StandardCharsets.UTF_8);
      return;
    }
    assertThat(COMMITTED).as("committed spec; generate it with -Dcontract.update=true").exists();
    String expected = Files.readString(COMMITTED, StandardCharsets.UTF_8);
    assertThat(actual)
        .as("The API changed but contracts/openapi/claims-service.json did not. If the change is intended, "
            + "regenerate it: mvn -pl services/claims-service test -Dtest=OpenApiContractTest "
            + "-Dcontract.update=true, and commit the new file (TS types are generated from it)")
        .isEqualTo(expected);
  }

  @Test
  void specCarriesSecurityProblemMoneyAndHeaders() {
    JsonNode spec = get("/v3/api-docs", null).json();
    // security scheme
    JsonNode bearer = spec.at("/components/securitySchemes/bearerAuth");
    assertThat(bearer.path("type").asString()).isEqualTo("http");
    assertThat(bearer.path("scheme").asString()).isEqualTo("bearer");
    assertThat(bearer.path("bearerFormat").asString()).isEqualTo("JWT");
    assertThat(spec.at("/paths/~1api~1v1~1staff~1claims/get/security/0").has("bearerAuth")).isTrue();
    assertThat(spec.at("/paths/~1api~1v1~1auth~1login/post/security").size()).as("login is public").isZero();

    // problem+json
    assertThat(spec.at("/components/schemas/Problem/properties/code").isMissingNode()).isFalse();
    assertThat(spec.at("/paths/~1api~1v1~1staff~1claims~1{id}~1approval/post/responses/409/content")
        .has("application/problem+json")).isTrue();

    // money as string
    assertThat(spec.at("/components/schemas/SubmitClaimRequest/properties/estimate/type").asString())
        .isEqualTo("string");
    assertThat(spec.at("/components/schemas/StaffClaimDetail/properties/estimate/type").asString())
        .isEqualTo("string");

    // headers
    assertThat(headerNames(spec.at("/paths/~1api~1v1~1staff~1claims~1{id}~1approval/post/parameters")))
        .contains("If-Match");
    assertThat(headerNames(spec.at("/paths/~1api~1v1~1claimant~1claims/post/parameters")))
        .contains("Idempotency-Key");
    assertThat(headerNames(spec.at("/paths/~1api~1v1~1claimant~1claims~1{id}~1info-responses/post/parameters")))
        .contains("If-Match");
  }

  private static List<String> headerNames(JsonNode parameters) {
    List<String> names = new ArrayList<>();
    for (JsonNode p : parameters) if ("header".equals(p.path("in").asString())) names.add(p.path("name").asString());
    return names;
  }

  /** Sorted keys, pretty-printed, no server URL (it carries the random test port). */
  static String normalise(JsonNode spec) {
    Object tree = JSON.convertValue(spec, Object.class);
    if (tree instanceof Map<?, ?> m) ((Map<?, ?>) m).remove("servers");
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(sorted(tree)) + "\n";
  }

  private static Object sorted(Object o) {
    if (o instanceof Map<?, ?> m) {
      Map<String, Object> out = new TreeMap<>();
      m.forEach((k, v) -> out.put(String.valueOf(k), sorted(v)));
      return out;
    }
    if (o instanceof List<?> l) return l.stream().map(OpenApiContractTest::sorted).toList();
    return o;
  }
}
