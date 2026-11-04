package com.chubb.claims.web;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Code-first OpenAPI (D10). springdoc reads the controllers; what it cannot see is added here so the
 * committed contract (contracts/openapi/claims-service.json) is complete: the bearer scheme, problem+json
 * errors (trap: stable {@code code}), money as strings (trap 8), required If-Match / Idempotency-Key
 * headers, the ETag response header, and 201 for claim submission. {@code OpenApiContractTest} fails when
 * any of it disappears.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {
  static final String BEARER = "bearerAuth";
  private static final String JSON = "application/json";

  static {
    // Money is a decimal string on the wire (JsonConfig), so it is a string in the spec too.
    SpringDocUtils.getConfig().replaceWithSchema(BigDecimal.class,
        new StringSchema().pattern("^-?\\d+(\\.\\d+)?$").description("Decimal amount as a string, in the claim currency"));
  }

  @Bean
  OpenApiCustomizer contractCustomizer() {
    return openApi -> {
      Components components = openApi.getComponents() == null ? new Components() : openApi.getComponents();
      openApi.setComponents(components);
      components.addSecuritySchemes(BEARER, new SecurityScheme().type(SecurityScheme.Type.HTTP)
          .scheme("bearer").bearerFormat("JWT").description("RS256 access token from POST /api/v1/auth/login"));
      ModelConverters.getInstance().readAll(ApiProblem.class).forEach(components::addSchemas);
      ModelConverters.getInstance().readAll(ApiProblem.FieldProblem.class).forEach(components::addSchemas);
      components.addSchemas("Problem", components.getSchemas().remove("ApiProblem"));

      nullables(components);
      openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, op) -> {
        boolean isPublic = path.equals("/api/v1/auth/login") || path.endsWith("/jwks.json");
        if (!isPublic) op.addSecurityItem(new SecurityRequirement().addList(BEARER));
        else op.setSecurity(java.util.List.of());
        headers(op);
        errors(op, path, isPublic, method.name());
        if (method.name().equals("POST") && (path.equals("/api/v1/claimant/claims") || path.endsWith("/documents"))) created(op);
        if (method.name().equals("GET") && path.endsWith("/content")) download(op);
        jsonOnly(op);
        etag(op);
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
    // ClaimantClaimDetail and StaffClaimDetail are NON_NULL themselves: their optional top-level fields are absent, not null.
    nullable(c, "DecisionView", "amount", "currency", "reasonCode", "explanation");
    nullable(c, "ClaimantUpdate", "fromStatus", "toStatus", "actorName");
    nullable(c, "MotorDetails", "thirdParty", "injury", "policeReport", "injuryDescription");
    nullable(c, "ThirdPartyDetails", "name", "phone", "insurer", "vehicleNumber");
    nullable(c, "PropertyDetails", "occupancy");
    nullable(c, "Ledger", "approvedAmount");
    nullable(c, "Deadlines", "firstPickedUpAt", "decidedAt", "closedAt");
    nullable(c, "InfoRequestView", "answer", "answeredAt");
    nullable(c, "StaffClaimSummary", "assignedOfficerName");
    nullable(c, "StaffFinancials", "approvedAmount");
    nullable(c, "Entry", "reason", "actorName");
    nullable(c, "DocumentView", "reviewNote", "reviewedAt");
    nullable(c, "ChecklistItem", "source", "reviewNote");
    for (String t : new String[] {"TimelineEntry"}) nullable(c, t, "fromStatus", "toStatus", "actorName");
    // LocalDateTime on the wire has no offset, so it is not an RFC 3339 date-time.
    for (String n : new String[] {"ClaimantClaimSummary", "ClaimantClaimDetail", "StaffClaimDetail", "SubmitClaimRequest"}) {
      Schema<?> p = (Schema<?>) c.getSchemas().get(n).getProperties().get("incidentAtLocal");
      p.setFormat(null);
      p.setPattern("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?$");
      p.setDescription("Local date and time in the claim's market zone, with no offset");
    }
  }

  private static void headers(Operation op) {
    if (op.getParameters() == null) return;
    for (Parameter p : op.getParameters()) {
      if ("header".equals(p.getIn()) && ("If-Match".equals(p.getName()) || "Idempotency-Key".equals(p.getName()))) {
        p.setRequired(true);
        p.setDescription("If-Match".equals(p.getName())
            ? "The claim's ETag from its last read, e.g. \"3\". Missing: 428 PRECONDITION_REQUIRED. Stale: 409 STALE_VERSION."
            : "Client-generated unique key; a repeat with the same key returns the original claim with 200.");
      }
    }
  }

  private static boolean hasHeader(Operation op, String name) {
    return op.getParameters() != null
        && op.getParameters().stream().anyMatch(p -> "header".equals(p.getIn()) && name.equals(p.getName()));
  }

  private static void errors(Operation op, String path, boolean isPublic, String method) {
    ApiResponses r = op.getResponses();
    boolean body = op.getRequestBody() != null;
    if (body || (op.getParameters() != null && op.getParameters().stream().anyMatch(p -> "query".equals(p.getIn())))
        || hasHeader(op, "Idempotency-Key")) {
      problem(r, "400", "Invalid request");
    }
    if (isPublic && path.endsWith("/login")) problem(r, "401", "Invalid credentials");
    if (!isPublic) {
      problem(r, "401", "Missing or invalid token");
      problem(r, "403", "Wrong role, or an action the caller may not take on this claim");
    }
    if (path.contains("{id}")) {
      problem(r, "400", "Invalid request, including an id that is not a UUID");
      problem(r, "404", "Not found, or not visible to the caller");
    }
    if (path.equals("/api/v1/auth/login")) problem(r, "422", "Missing or blank email or password");
    if (body && !path.equals("/api/v1/auth/login")) problem(r, "415", "Request body is not in a supported media type");
    if (path.equals("/api/v1/claimant/claims") && method.equals("POST")) problem(r, "404", "Policy not found, or not the caller's");
    if (path.endsWith("/documents") && method.equals("POST")) problem(r, "409", "CLAIM_CLOSED, or TOO_MANY_DOCUMENTS");
    if (path.endsWith("/review")) problem(r, "409", "ILLEGAL_TRANSITION (claim is closed), or the document was already re-requested");
    if (hasHeader(op, "If-Match")) {
      problem(r, "409", "STALE_VERSION, or a claim state that does not allow the action");
      problem(r, "428", "If-Match header missing");
    }
    if (method.equals("POST") && !isPublic) problem(r, "422", "Business rule or validation failure");
    if (hasHeader(op, "Idempotency-Key")) problem(r, "409", "Idempotency conflict");
  }

  private static void problem(ApiResponses r, String code, String description) {
    if (r.containsKey(code)) return;
    r.addApiResponse(code, new ApiResponse().description(description).content(new Content().addMediaType(
        "application/problem+json", new MediaType().schema(new Schema<>().$ref("#/components/schemas/Problem")))));
  }

  /** A document download answers with the stored file, not JSON. */
  private static void download(Operation op) {
    ApiResponse ok = op.getResponses().get("200");
    if (ok == null) return;
    Content c = new Content();
    for (String type : new String[] {"application/pdf", "image/jpeg", "image/png"}) {
      c.addMediaType(type, new MediaType().schema(new StringSchema().format("binary")));
    }
    ok.setDescription("The file as uploaded, with its detected content type");
    ok.setContent(c);
  }

  private static void created(Operation op) {
    ApiResponse ok = op.getResponses().get("200");
    if (ok == null) return;
    boolean claim = op.getParameters() != null && hasHeader(op, "Idempotency-Key");
    ApiResponse created = new ApiResponse().description(claim ? "Claim created" : "Document stored").content(ok.getContent());
    ok.setDescription(claim ? "Existing claim returned for a repeated Idempotency-Key" : "Document stored");
    ApiResponses ordered = new ApiResponses();
    ordered.addApiResponse("201", created);
    ordered.addApiResponse("200", ok);
    op.getResponses().forEach((k, v) -> { if (!k.equals("200")) ordered.putIfAbsent(k, v); });
    op.setResponses(ordered);
  }

  private static void jsonOnly(Operation op) {
    for (Map.Entry<String, ApiResponse> e : op.getResponses().entrySet()) {
      Content c = e.getValue().getContent();
      if (c != null && c.containsKey("*/*")) {
        Content rewritten = new Content();
        new LinkedHashMap<>(c).forEach((k, v) -> rewritten.addMediaType(k.equals("*/*") ? JSON : k, v));
        e.getValue().setContent(rewritten);
      }
    }
  }

  private static void etag(Operation op) {
    for (String code : new String[] {"200", "201"}) {
      ApiResponse r = op.getResponses().get(code);
      if (r == null || r.getContent() == null || !r.getContent().containsKey(JSON)) continue;
      Schema<?> s = r.getContent().get(JSON).getSchema();
      String ref = s == null ? null : s.get$ref();
      if (ref != null && (ref.endsWith("/ClaimantClaimDetail") || ref.endsWith("/StaffClaimDetail"))) {
        r.addHeaderObject("ETag", new Header().description("The claim's version, quoted; send it back as If-Match")
            .schema(new StringSchema()));
      }
    }
  }
}
