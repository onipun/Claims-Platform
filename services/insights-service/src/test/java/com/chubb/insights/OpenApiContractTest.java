package com.chubb.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.insights.support.InsightsTestBase;
import com.chubb.insights.support.TestTokens;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * D10: the committed spec is the contract. To accept a deliberate change:
 * {@code mvn -pl services/insights-service test -Dtest=OpenApiContractTest -Dcontract.update=true}.
 */
class OpenApiContractTest extends InsightsTestBase {
  static final Path COMMITTED = Path.of("..", "..", "contracts", "openapi", "insights-service.json");
  static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort int port;

  private JsonNode spec() throws Exception {
    HttpResponse<String> r = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(200);
    return JSON.readTree(r.body());
  }

  @Test
  void committedSpecMatchesTheRunningApplication() throws Exception {
    String actual = normalise(spec());
    if (Boolean.getBoolean("contract.update")) {
      Files.createDirectories(COMMITTED.getParent());
      Files.writeString(COMMITTED, actual, StandardCharsets.UTF_8);
      return;
    }
    assertThat(COMMITTED).as("committed spec; generate it with -Dcontract.update=true").exists();
    assertThat(actual)
        .as("The API changed but contracts/openapi/insights-service.json did not. If intended, regenerate: "
            + "mvn -pl services/insights-service test -Dtest=OpenApiContractTest -Dcontract.update=true")
        .isEqualTo(Files.readString(COMMITTED, StandardCharsets.UTF_8));
  }

  @Test
  void specCarriesSecurityProblemAndMoneyAsStrings() throws Exception {
    JsonNode spec = spec();
    assertThat(spec.at("/components/securitySchemes/bearerAuth/scheme").asString()).isEqualTo("bearer");
    for (String p : List.of("exposure", "workload", "performance")) {
      assertThat(spec.at("/paths/~1api~1v1~1insights~1" + p + "/get/security/0").has("bearerAuth")).as(p).isTrue();
      assertThat(spec.at("/paths/~1api~1v1~1insights~1" + p + "/get/responses/403/content")
          .has("application/problem+json")).as(p).isTrue();
    }
    assertThat(spec.at("/components/schemas/ExposureRow/properties/caseOutstanding/type").asString()).isEqualTo("string");
    assertThat(spec.at("/components/schemas/ExposureResponse/properties/indicativeUsdTotal").isMissingNode()).isFalse();
    assertThat(spec.at("/components/schemas/Problem/properties/code").isMissingNode()).isFalse();
  }

  static String normalise(JsonNode spec) {
    Object tree = JSON.convertValue(spec, Object.class);
    if (tree instanceof Map<?, ?> m) m.remove("servers");
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
