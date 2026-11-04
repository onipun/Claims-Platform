package com.chubb.insights.config;

import com.chubb.insights.web.ApiProblem;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.math.BigDecimal;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Code-first OpenAPI (D10). {@code OpenApiContractTest} fails when the committed spec drifts. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {
  static final String BEARER = "bearerAuth";

  static {
    SpringDocUtils.getConfig().replaceWithSchema(BigDecimal.class,
        new StringSchema().pattern("^-?\\d+(\\.\\d+)?$").description("Decimal amount as a string; scale 2, or 0 for IDR"));
  }

  @Bean
  OpenAPI insightsApi() {
    return new OpenAPI().info(new Info().title("insights-service").version("0.1.0").description(
        "Team views built from claims.events.v1. Staff only; every figure is scoped to the token's markets "
            + "and carries asOf, the newest event time in scope (eventual consistency)."));
  }

  @Bean
  OpenApiCustomizer contractCustomizer() {
    return openApi -> {
      Components components = openApi.getComponents() == null ? new Components() : openApi.getComponents();
      openApi.setComponents(components);
      components.addSecuritySchemes(BEARER, new SecurityScheme().type(SecurityScheme.Type.HTTP)
          .scheme("bearer").bearerFormat("JWT").description("RS256 access token issued by claims-service"));
      ModelConverters.getInstance().readAll(ApiProblem.class).forEach(components::addSchemas);
      components.addSchemas("Problem", components.getSchemas().remove("ApiProblem"));
      nullables(components);
      openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, op) -> {
        op.addSecurityItem(new SecurityRequirement().addList(BEARER));
        ApiResponses r = op.getResponses();
        problem(r, "400", "Invalid parameter (market not two capital letters, days outside 1..365)");
        problem(r, "401", "Missing or invalid token");
        problem(r, "403", "Not staff");
      }));
    };
  }

  /**
   * The server writes an optional value as an explicit null (see the DTO records), so the contract says so:
   * the property's type gains "null", an enum gains null, and a $ref becomes oneOf [ref, null]. A name that
   * no longer exists fails startup of the docs, so the table cannot rot silently.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  static void nullable(Components components, String schema, String... props) {
    Schema<?> s = components.getSchemas().get(schema);
    if (s == null) throw new IllegalStateException("nullable(): no schema " + schema);
    for (String name : props) {
      Schema p = s.getProperties() == null ? null : (Schema) s.getProperties().get(name);
      if (p == null) throw new IllegalStateException("nullable(): no property " + schema + "." + name);
      if (p.get$ref() != null) {
        Schema wrapped = new Schema<>();
        Schema ref = new Schema<>();
        ref.set$ref(p.get$ref());
        Schema nul = new Schema<>();
        nul.addType("null");
        wrapped.setOneOf(new java.util.ArrayList<Schema>(java.util.List.of(ref, nul)));
        s.getProperties().put(name, wrapped);
        continue;
      }
      java.util.Set<String> types = new java.util.LinkedHashSet<>();
      if (p.getTypes() != null) types.addAll(p.getTypes());
      else if (p.getType() != null) types.add(p.getType());
      types.add("null");
      p.setTypes(types);
      p.setType(null);
      if (p.getEnum() != null && !p.getEnum().contains(null)) p.getEnum().add(null);
    }
  }

  private static void nullables(Components c) {
    nullable(c, "ExposureResponse", "asOf", "indicativeUsdTotal");
    nullable(c, "IndicativeUsdTotal", "note");
    nullable(c, "WorkloadResponse", "asOf");
    nullable(c, "OfficerWorkload", "officerId", "officerName", "oldestAgeDays", "oldestSubmittedAt");
    nullable(c, "PerformanceResponse", "asOf");
    nullable(c, "PerformanceRow", "name", "approvalRate", "avgHoursToFirstPickup", "medianHoursToFirstPickup",
        "avgHoursToDecision", "avgHoursToSettlement");
  }

  private static void problem(ApiResponses r, String code, String description) {
    if (r.containsKey(code)) return;
    r.addApiResponse(code, new ApiResponse().description(description).content(new Content().addMediaType(
        "application/problem+json", new MediaType().schema(new Schema<>().$ref("#/components/schemas/Problem")))));
  }
}
