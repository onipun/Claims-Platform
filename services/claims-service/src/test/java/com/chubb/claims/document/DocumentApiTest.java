package com.chubb.claims.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.claims.claim.domain.Command;
import com.chubb.claims.support.StaffApiTest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

class DocumentApiTest extends StaffApiTest {
  @Autowired @MockitoSpyBean DocumentStore store;
  @Autowired @MockitoSpyBean com.chubb.claims.claim.ClaimActivityRepository activityRepo;

  static final byte[] PNG = Base64.getDecoder().decode(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private String docs(UUID claim) { return CLAIMANT_CLAIMS + "/" + claim + "/documents"; }

  /** Hand-made multipart body so the declared type and filename are fully under the test's control. */
  private Resp upload(String who, UUID claim, String type, String filename, String declared, byte[] bytes) {
    String boundary = "----t" + UUID.randomUUID();
    ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 512);
    try {
      out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"documentType\"\r\n\r\n" + type + "\r\n")
          .getBytes(StandardCharsets.UTF_8));
      out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
          + "\"\r\nContent-Type: " + declared + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
      out.write(bytes);
      out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
      HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + docs(claim)))
          .header("Authorization", "Bearer " + login(who))
          .header("Content-Type", "multipart/form-data; boundary=" + boundary)
          .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
      HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
      return new Resp(r.statusCode(), r.body(), r.headers());
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  private HttpResponse<byte[]> download(String who, String path) throws Exception {
    return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Authorization", "Bearer " + login(who)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private UUID uploaded(UUID claim, String type) {
    Resp r = upload("claimant.sg", claim, type, "photo.png", "image/png", PNG);
    assertThat(r.status()).as(r.body()).isEqualTo(201);
    return UUID.fromString(r.json().get("id").asString());
  }

  private int activities(UUID claim, String type, String visibility) {
    return jdbc.queryForObject("select count(*) from claim_activity where claim_id = ? and activity_type = ? and visibility = ?",
        Integer.class, claim, type, visibility);
  }

  @Test
  void checklistMergesRequirementsWithUploadsAndSaysWhetherPlaceholder() {
    UUID claim = newClaim("1000");
    JsonNode c = get(docs(claim), login("claimant.sg")).json();
    assertThat(c.get("items").size()).isEqualTo(5); // OWN_DAMAGE
    JsonNode first = c.get("items").get(0);
    assertThat(first.get("status").asString()).isEqualTo("MISSING");
    assertThat(first.get("placeholder").asBoolean()).isTrue(); // SG list is a placeholder copy of the MY source
    assertThat(c.get("outstanding").asInt()).isEqualTo(5);

    UUID doc = uploaded(claim, "POLICE_REPORT");
    c = get(docs(claim), login("claimant.sg")).json();
    JsonNode police = null;
    for (JsonNode i : c.get("items")) if (i.get("documentType").asString().equals("POLICE_REPORT")) police = i;
    assertThat(police.get("status").asString()).isEqualTo("UPLOADED");
    assertThat(police.get("uploads").get(0).get("id").asString()).isEqualTo(doc.toString());
    assertThat(c.get("outstanding").asInt()).isEqualTo(4);
    assertThat(c.toString()).doesNotContain("storageKey").doesNotContain("storage_key");
  }

  /** R9: the checklist of every motor claim type names exactly its documents; a property claim has none. */
  @Test
  void theChecklistNamesExactlyTheDocumentsOfEachClaimType() {
    for (var e : com.chubb.claims.MigrationTest.MOTOR_DOCUMENTS.entrySet()) {
      java.util.Map<String, Object> body = motorBody(policyId("MOT-SG-100001"), "2026-03-10T09:00:00", "100");
      body.put("claimType", e.getKey());
      Resp r = post(CLAIMANT_CLAIMS, login("claimant.sg"), toJson(body), "Idempotency-Key", key());
      assertThat(r.status()).as(r.body()).isEqualTo(201);
      UUID claim = UUID.fromString(r.json().get("id").asString());
      List<String> types = new java.util.ArrayList<>();
      for (JsonNode i : get(docs(claim), login("claimant.sg")).json().get("items")) types.add(i.get("documentType").asString());
      assertThat(types).as(e.getKey()).containsExactlyInAnyOrderElementsOf(e.getValue());
      List<String> staff = new java.util.ArrayList<>();
      for (JsonNode i : get(STAFF_CLAIMS + claim + "/documents", login("officer.sg")).json().get("items")) {
        staff.add(i.get("documentType").asString());
      }
      assertThat(staff).as("staff view of " + e.getKey()).containsExactlyInAnyOrderElementsOf(e.getValue());
    }
    java.util.Map<String, Object> property = new java.util.HashMap<>();
    property.put("policyId", policyId("PRP-SG-100002").toString());
    property.put("line", "PROPERTY");
    property.put("claimType", "FIRE");
    property.put("incidentAtLocal", "2026-03-10T09:00:00");
    property.put("estimate", "100");
    property.put("property", java.util.Map.of("address", "1 Road", "cause", "fire"));
    Resp pr = post(CLAIMANT_CLAIMS, login("claimant.sg"), toJson(property), "Idempotency-Key", key());
    assertThat(pr.status()).as(pr.body()).isEqualTo(201);
    assertThat(get(docs(UUID.fromString(pr.json().get("id").asString())), login("claimant.sg")).json().get("items").size()).isZero();
  }

  @Test
  void uploadAndDownloadRoundTripWithExactHeadersChecksumAndAudit() throws Exception {
    UUID claim = newClaim("1000");
    long versionBefore = version(claim);
    UUID doc = uploaded(claim, "POLICE_REPORT");
    int before = activities(claim, "DOCUMENT_DOWNLOADED", "INTERNAL");

    HttpResponse<byte[]> r = download("claimant.sg", docs(claim) + "/" + doc + "/content");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(sha(r.body())).isEqualTo(sha(PNG));
    assertThat(r.headers().allValues("Content-Type")).containsExactly("image/png");
    assertThat(r.headers().allValues("Content-Disposition")).containsExactly("attachment; filename=\"photo.png\"");
    assertThat(r.headers().allValues("X-Content-Type-Options")).containsExactly("nosniff");
    assertThat(r.headers().firstValue("Content-Length")).contains(String.valueOf(PNG.length));
    assertThat(activities(claim, "DOCUMENT_DOWNLOADED", "INTERNAL")).isEqualTo(before + 1);

    // staff download is a second audited read, and staff of the market see it
    assertThat(download("officer.sg", STAFF_CLAIMS + claim + "/documents/" + doc + "/content").statusCode()).isEqualTo(200);
    assertThat(activities(claim, "DOCUMENT_DOWNLOADED", "INTERNAL")).isEqualTo(before + 2);
    // none of it is visible to the claimant's timeline, and none of it bumps the version or emits events (6.4)
    assertThat(claimantGet(claim).toString()).doesNotContain("DOCUMENT_");
    assertThat(version(claim)).isEqualTo(versionBefore);
  }

  @Test
  void contentIsCheckedByMagicBytesNotByDeclaredType() {
    UUID claim = newClaim("1000");
    Resp fake = upload("claimant.sg", claim, "POLICE_REPORT", "evil.png", "image/png",
        "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
    assertThat(fake.status()).isEqualTo(422);
    assertThat(fake.json().get("code").asString()).isEqualTo("INVALID_FILE_TYPE");
    assertThat(upload("claimant.sg", claim, "POLICE_REPORT", "empty.png", "image/png", new byte[0]).status()).isEqualTo(422);
    // a real PDF declared as text is accepted and stored as application/pdf
    Resp pdf = upload("claimant.sg", claim, "REPAIRER_ESTIMATE", "quote.txt", "text/plain",
        "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.UTF_8));
    assertThat(pdf.status()).as(pdf.body()).isEqualTo(201);
    assertThat(pdf.json().get("contentType").asString()).isEqualTo("application/pdf");
    assertThat(upload("claimant.sg", claim, "NOT_A_TYPE", "a.png", "image/png", PNG).status()).isEqualTo(422);
    assertThat(jdbc.queryForObject("select count(*) from claim_document where claim_id = ?", Integer.class, claim))
        .isEqualTo(1);
  }

  @Test
  void overTenMegabytesIs413ProblemJson() {
    UUID claim = newClaim("1000");
    byte[] big = new byte[10 * 1024 * 1024 + 1];
    System.arraycopy(PNG, 0, big, 0, PNG.length);
    Resp r = upload("claimant.sg", claim, "POLICE_REPORT", "big.png", "image/png", big);
    assertThat(r.status()).isEqualTo(413);
    assertThat(r.header("Content-Type")).startsWith("application/problem+json");
    assertThat(r.json().get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
    assertThat(jdbc.queryForObject("select count(*) from claim_document where claim_id = ?", Integer.class, claim)).isZero();
  }

  @Test
  void filenameCannotEscapeAndStorageKeyIsNotDerivedFromIt() throws Exception {
    UUID claim = newClaim("1000");
    Resp r = upload("claimant.sg", claim, "POLICE_REPORT", "../../evil.png", "image/png", PNG);
    assertThat(r.status()).as(r.body()).isEqualTo(201);
    assertThat(r.json().get("filename").asString()).isEqualTo("evil.png");
    UUID id = UUID.fromString(r.json().get("id").asString());
    String key = jdbc.queryForObject("select storage_key from claim_document where id = ?", String.class, id);
    assertThat(key).matches("[0-9a-f-]{36}").doesNotContain("evil").isNotEqualTo(id.toString());
    Path dir = Path.of(System.getProperty("java.io.tmpdir"), "claims-documents");
    assertThat(Files.exists(dir.resolve(key))).isTrue();
    assertThat(Files.exists(dir.resolve("evil.png"))).isFalse();
    assertThat(Files.exists(dir.getParent().resolve("evil.png"))).isFalse();
    assertThat(Files.exists(dir.getParent().getParent().resolve("evil.png"))).isFalse();
    HttpResponse<byte[]> d = download("claimant.sg", docs(claim) + "/" + id + "/content");
    assertThat(d.headers().allValues("Content-Disposition")).containsExactly("attachment; filename=\"evil.png\"");
    // other odd names are neutralised
    Resp odd = upload("claimant.sg", claim, "OTHER", "a\\b\\..\\x;y z.png", "image/png", PNG);
    assertThat(odd.json().get("filename").asString()).matches("[A-Za-z0-9._-]+");
  }

  @Test
  void anotherClaimantsOrAnotherMarketsDocumentIs404IdenticalToNotFound() throws Exception {
    UUID claim = newClaim("1000");
    UUID doc = uploaded(claim, "POLICE_REPORT");
    String missing = "/" + UUID.randomUUID() + "/content";

    HttpResponse<byte[]> other = download("claimant2.sg", docs(claim) + "/" + doc + "/content");
    HttpResponse<byte[]> unknown = download("claimant2.sg", docs(claim) + missing);
    assertThat(other.statusCode()).isEqualTo(404);
    assertThat(new String(other.body())).isEqualTo(new String(unknown.body()));
    assertThat(get(docs(claim), login("claimant2.sg")).status()).isEqualTo(404);
    assertThat(upload("claimant2.sg", claim, "OTHER", "a.png", "image/png", PNG).status()).isEqualTo(404);

    HttpResponse<byte[]> hk = download("officer.hk", STAFF_CLAIMS + claim + "/documents/" + doc + "/content");
    HttpResponse<byte[]> hkUnknown = download("officer.hk", STAFF_CLAIMS + claim + "/documents" + missing);
    assertThat(hk.statusCode()).isEqualTo(404);
    assertThat(new String(hk.body())).isEqualTo(new String(hkUnknown.body()));

    // a document of claim A is not reachable through claim B of the same claimant
    UUID claimB = newClaim("1000");
    assertThat(download("claimant.sg", docs(claimB) + "/" + doc + "/content").statusCode()).isEqualTo(404);
  }

  @Test
  void staffReviewRequestsAgainAndClaimantCanReuploadThenStaffAccepts() {
    UUID claim = newClaim("1000");
    staffSide.run(claim, Command.pickUp(officerSg()));
    UUID doc = uploaded(claim, "POLICE_REPORT");
    String review = STAFF_CLAIMS + claim + "/documents/" + doc + "/review";

    assertThat(post(review, login("officer2.sg"), "{\"decision\":\"ACCEPTED\"}").status()).isEqualTo(404); // not assigned
    assertThat(post(review, login("officer.hk"), "{\"decision\":\"ACCEPTED\"}").status()).isEqualTo(404);
    assertThat(post(review, login("officer.sg"), "{\"decision\":\"REREQUESTED\"}").status()).isEqualTo(422); // reason needed
    long v = version(claim);
    Resp rr = post(review, login("officer.sg"), "{\"decision\":\"REREQUESTED\",\"reason\":\"Photo is blurry\"}");
    assertThat(rr.status()).as(rr.body()).isEqualTo(200);
    assertThat(rr.json().get("status").asString()).isEqualTo("RE_REQUESTED");
    assertThat(version(claim)).isEqualTo(v);
    assertThat(post(review, login("officer.sg"), "{\"decision\":\"ACCEPTED\"}").status()).isEqualTo(409);

    assertThat(claimantGet(claim).toString()).contains("Photo is blurry");
    assertThat(get("/api/v1/claimant/updates", login("claimant.sg")).body()).contains("Photo is blurry");
    JsonNode list = get(docs(claim), login("claimant.sg")).json();
    JsonNode item = null;
    for (JsonNode i : list.get("items")) if (i.get("documentType").asString().equals("POLICE_REPORT")) item = i;
    assertThat(item.get("status").asString()).isEqualTo("RE_REQUESTED");
    assertThat(item.get("reviewNote").asString()).isEqualTo("Photo is blurry");

    UUID second = uploaded(claim, "POLICE_REPORT");
    String review2 = STAFF_CLAIMS + claim + "/documents/" + second + "/review";
    Resp acc = post(review2, login("manager.sg"), "{\"decision\":\"ACCEPTED\"}");
    assertThat(acc.status()).as(acc.body()).isEqualTo(200);
    assertThat(acc.json().get("status").asString()).isEqualTo("ACCEPTED");
    assertThat(claimantGet(claim).toString()).contains("Document accepted");
  }

  @Test
  void noUploadOnTerminalClaim() {
    UUID claim = newClaim("1000");
    ok("officer.sg", claim, "pick-up", null);
    ok("officer.sg", claim, "rejection", Map.of("reason", "OTHER", "explanation", "No cover"));
    Resp r = upload("claimant.sg", claim, "POLICE_REPORT", "a.png", "image/png", PNG);
    assertThat(r.status()).isEqualTo(409);
    assertThat(r.json().get("code").asString()).isEqualTo("CLAIM_CLOSED");
  }

  @Test
  void noUploadOnSettledClaim() {
    UUID claim = newClaim("1000");
    ok("officer.sg", claim, "pick-up", null);
    ok("officer.sg", claim, "assessment", Map.of("reserve", "800"));
    ok("manager.sg", claim, "approval", Map.of("amount", "800.00"));
    ok("officer.sg", claim, "settlement", Map.of("amount", "800.00"));
    Resp r = upload("claimant.sg", claim, "POLICE_REPORT", "a.png", "image/png", PNG);
    assertThat(r.status()).isEqualTo(409);
    assertThat(r.json().get("code").asString()).isEqualTo("CLAIM_CLOSED");
    assertThat(jdbc.queryForObject("select count(*) from claim_document where claim_id = ?", Integer.class, claim)).isZero();
  }

  @Test
  void reviewOnSettledOrRejectedClaimIs409IllegalTransitionWithNoChangeAndNoActivity() {
    for (String end : new String[] {"SETTLED", "REJECTED"}) {
      UUID claim = newClaim("1000");
      ok("officer.sg", claim, "pick-up", null);
      UUID doc = uploaded(claim, "POLICE_REPORT");
      if (end.equals("SETTLED")) {
        ok("officer.sg", claim, "assessment", Map.of("reserve", "4000"));
        ok("manager.sg", claim, "approval", Map.of("amount", "4000.00"));
        ok("officer.sg", claim, "settlement", Map.of("amount", "4000.00"));
      } else {
        ok("officer.sg", claim, "rejection", Map.of("reason", "OTHER", "explanation", "No cover"));
      }
      int before = jdbc.queryForObject("select count(*) from claim_activity where claim_id = ?", Integer.class, claim);
      long v = version(claim);
      String review = STAFF_CLAIMS + claim + "/documents/" + doc + "/review";
      for (String body : new String[] {"{\"decision\":\"ACCEPTED\"}",
          "{\"decision\":\"REREQUESTED\",\"reason\":\"Again\"}"}) {
        Resp r = post(review, login("officer.sg"), body);
        assertThat(r.status()).as(end + " " + r.body()).isEqualTo(409);
        assertThat(r.json().get("code").asString()).isEqualTo("ILLEGAL_TRANSITION");
      }
      assertThat(post(review, login("officer2.sg"), "{\"decision\":\"ACCEPTED\"}").status()).as("access first").isEqualTo(404);
      assertThat(jdbc.queryForObject("select status from claim_document where id = ?", String.class, doc))
          .isEqualTo("UPLOADED");
      assertThat(jdbc.queryForObject("select count(*) from claim_activity where claim_id = ?", Integer.class, claim))
          .isEqualTo(before);
      assertThat(version(claim)).isEqualTo(v);
    }
  }

  @Test
  void staffOnTheirOwnClaimGetConflictOfInterestOnDocuments() throws Exception {
    UUID claim = newClaim("1000");
    UUID doc = uploaded(claim, "POLICE_REPORT");
    jdbc.update("update claim set claimant_id = ?, policy_id = ? where id = ?", userId("officer2.sg"), policyId("MOT-SG-100005"), claim);
    HttpResponse<byte[]> r = download("officer2.sg", STAFF_CLAIMS + claim + "/documents/" + doc + "/content");
    assertThat(r.statusCode()).isEqualTo(403);
    assertThat(new String(r.body())).contains("CONFLICT_OF_INTEREST");
  }

  @Test
  void aPolicyholderWhoIsStaffGetsConflictOfInterestOnContentAndOnReviewAndNothingChanges() {
    UUID claim = newClaim("1000");
    UUID doc = uploaded(claim, "POLICE_REPORT");
    jdbc.update("update claim set claimant_id = ?, assigned_officer_id = ?, policy_id = ? where id = ?",
        userId("officer2.sg"), userId("officer2.sg"), policyId("MOT-SG-100005"), claim); // assigned too, so only the conflict can be the reason
    String statusBefore = jdbc.queryForObject("select status from claim_document where id = ?", String.class, doc);
    long activitiesBefore = jdbc.queryForObject("select count(*) from claim_activity where claim_id = ?", Long.class, claim);
    String base = STAFF_CLAIMS + claim + "/documents/" + doc;

    Resp content = get(base + "/content", login("officer2.sg"));
    assertThat(content.status()).as(content.body()).isEqualTo(403);
    assertThat(content.json().get("code").asString()).isEqualTo("CONFLICT_OF_INTEREST");
    Resp review = post(base + "/review", login("officer2.sg"), "{\"decision\":\"ACCEPTED\"}");
    assertThat(review.status()).as(review.body()).isEqualTo(403);
    assertThat(review.json().get("code").asString()).isEqualTo("CONFLICT_OF_INTEREST");

    assertThat(jdbc.queryForObject("select status from claim_document where id = ?", String.class, doc)).isEqualTo(statusBefore);
    assertThat(jdbc.queryForObject("select count(*) from claim_activity where claim_id = ?", Long.class, claim))
        .isEqualTo(activitiesBefore);
  }

  private static String sha(byte[] b) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
  }

  // ---- group 2 ----

  private ListAppender<ILoggingEvent> errors;

  @BeforeEach
  void captureErrors() {
    errors = new ListAppender<>();
    errors.start();
    Logger l = (Logger) LoggerFactory.getLogger(com.chubb.claims.web.ApiExceptionHandler.class);
    l.addAppender(errors);
    l.setLevel(Level.INFO);
  }

  @AfterEach
  void releaseErrors() {
    ((Logger) LoggerFactory.getLogger(com.chubb.claims.web.ApiExceptionHandler.class)).detachAppender(errors);
  }

  private List<ILoggingEvent> errorEvents() {
    return errors.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
  }

  private static final Path DOCS_DIR = Path.of(System.getProperty("java.io.tmpdir"), "claims-documents");

  private long filesOnDisk() throws IOException {
    try (var s = Files.list(DOCS_DIR)) {
      return s.count();
    }
  }

  private JsonNode checklistItem(UUID claim, String who, String type, boolean staff) {
    JsonNode list = get(staff ? STAFF_CLAIMS + claim + "/documents" : docs(claim), login(who)).json();
    for (JsonNode i : list.get("items")) if (i.get("documentType").asString().equals(type)) return i;
    throw new AssertionError(type + " not on the checklist: " + list);
  }

  /** I3 */
  @Test
  void aPdfNamedDotHtmlIsStoredAndServedWithADotPdfExtension() throws Exception {
    UUID claim = newClaim("1000");
    Resp r = upload("claimant.sg", claim, "POLICE_REPORT", "x.html", "text/html",
        "%PDF-1.7\n<html><script>alert(1)</script>\n%%EOF".getBytes(StandardCharsets.UTF_8));
    assertThat(r.status()).as(r.body()).isEqualTo(201);
    assertThat(r.json().get("filename").asString()).isEqualTo("x.pdf");
    assertThat(r.json().get("contentType").asString()).isEqualTo("application/pdf");
    UUID id = UUID.fromString(r.json().get("id").asString());
    assertThat(jdbc.queryForObject("select filename from claim_document where id = ?", String.class, id))
        .as("the stored name too").isEqualTo("x.pdf");

    for (String path : List.of(docs(claim) + "/" + id + "/content", STAFF_CLAIMS + claim + "/documents/" + id + "/content")) {
      HttpResponse<byte[]> d = download(path.startsWith("/api/v1/staff") ? "officer.sg" : "claimant.sg", path);
      assertThat(d.statusCode()).isEqualTo(200);
      assertThat(d.headers().allValues("Content-Disposition")).containsExactly("attachment; filename=\"x.pdf\"");
      assertThat(d.headers().allValues("X-Content-Type-Options")).containsExactly("nosniff");
      assertThat(d.headers().allValues("Content-Type")).containsExactly("application/pdf");
    }
    // the extension is rebuilt for every type, and no inner extension survives
    assertThat(FileTypes.displayName("a.b.html", "image/png")).isEqualTo("a_b.png");
    assertThat(FileTypes.displayName("noext", "image/jpeg")).isEqualTo("noext.jpg");
    assertThat(FileTypes.displayName(".html", "application/pdf")).isEqualTo("html.pdf");
    assertThat(FileTypes.displayName(null, "application/pdf")).isEqualTo("document.pdf");
  }

  /** I2 */
  @Test
  void aStaffNoteReachesTheClaimantOnlyWhileTheDocumentIsReRequested() {
    UUID claim = newClaim("1000");
    staffSide.run(claim, Command.pickUp(officerSg()));
    UUID doc = uploaded(claim, "POLICE_REPORT");
    String review = STAFF_CLAIMS + claim + "/documents/" + doc + "/review";

    Resp acc = post(review, login("officer.sg"), "{\"decision\":\"ACCEPTED\",\"reason\":\"INTERNAL-SUSPECT-FRAUD-RING\"}");
    assertThat(acc.status()).as(acc.body()).isEqualTo(200);
    assertThat(acc.json().get("reviewNote").asString()).as("staff see the note").isEqualTo("INTERNAL-SUSPECT-FRAUD-RING");
    assertThat(checklistItem(claim, "officer.sg", "POLICE_REPORT", true).toString()).contains("INTERNAL-SUSPECT-FRAUD-RING");
    assertThat(get(docs(claim), login("claimant.sg")).body()).as("ACCEPTED: hidden from the claimant")
        .doesNotContain("INTERNAL-SUSPECT-FRAUD-RING");
    assertThat(claimantGet(claim).toString()).doesNotContain("INTERNAL-SUSPECT-FRAUD-RING");
    assertThat(get("/api/v1/claimant/updates", login("claimant.sg")).body()).doesNotContain("INTERNAL-SUSPECT-FRAUD-RING");

    UUID second = uploaded(claim, "REPAIRER_ESTIMATE");
    Resp rr = post(STAFF_CLAIMS + claim + "/documents/" + second + "/review", login("officer.sg"),
        "{\"decision\":\"RE_REQUESTED\",\"reason\":\"Please send a sharper scan\"}");
    assertThat(rr.status()).as(rr.body()).isEqualTo(200);
    JsonNode item = checklistItem(claim, "claimant.sg", "REPAIRER_ESTIMATE", false);
    assertThat(item.get("status").asString()).isEqualTo("RE_REQUESTED");
    assertThat(item.get("reviewNote").asString()).as("RE_REQUESTED: the claimant sees why")
        .isEqualTo("Please send a sharper scan");
    assertThat(item.get("uploads").get(0).get("reviewNote").asString()).isEqualTo("Please send a sharper scan");
    assertThat(checklistItem(claim, "claimant.sg", "POLICE_REPORT", false).get("reviewNote").isNull()).isTrue();
  }

  /** I15: a record whose file is gone is the server's fault; an unknown record is a plain 404. */
  @Test
  void aMissingFileOnDiskIs500WithAnErrorIdAndALogLineButAMissingRecordStays404() throws Exception {
    UUID claim = newClaim("1000");
    UUID doc = uploaded(claim, "POLICE_REPORT");
    String key = jdbc.queryForObject("select storage_key from claim_document where id = ?", String.class, doc);

    assertThat(download("claimant.sg", docs(claim) + "/" + UUID.randomUUID() + "/content").statusCode()).isEqualTo(404);
    assertThat(errorEvents()).as("a missing record is not an error").isEmpty();

    Files.delete(DOCS_DIR.resolve(key));
    HttpResponse<byte[]> r = download("claimant.sg", docs(claim) + "/" + doc + "/content");
    assertThat(r.statusCode()).isEqualTo(500);
    JsonNode body = JSON.readTree(new String(r.body(), StandardCharsets.UTF_8));
    assertThat(body.get("code").asString()).isEqualTo("INTERNAL_ERROR");
    String errorId = body.get("errorId").asString();
    assertThat(errorEvents()).hasSize(1);
    assertThat(errorEvents().get(0).getFormattedMessage()).contains(errorId);
    assertThat(errorEvents().get(0).getThrowableProxy()).as("with the cause").isNotNull();
    assertThat(new String(r.body(), StandardCharsets.UTF_8)).doesNotContain(key).doesNotContain(DOCS_DIR.toString());
  }

  /** I15: a failing store is a 500 with an errorId, not a silent 400. */
  @Test
  void aStoreFailureOnUploadIs500WithAnErrorIdAndLeavesNoRecord() throws Exception {
    UUID claim = newClaim("1000");
    org.mockito.Mockito.doThrow(new IOException("disk full")).when(store).save(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any());
    Resp r = upload("claimant.sg", claim, "POLICE_REPORT", "a.png", "image/png", PNG);
    assertThat(r.status()).isEqualTo(500);
    assertThat(r.json().get("code").asString()).isEqualTo("INTERNAL_ERROR");
    assertThat(r.body()).doesNotContain("disk full");
    assertThat(errorEvents()).hasSize(1);
    assertThat(errorEvents().get(0).getFormattedMessage()).contains(r.json().get("errorId").asString());
    assertThat(jdbc.queryForObject("select count(*) from claim_document where claim_id = ?", Integer.class, claim)).isZero();
  }

  /** I15: the client's own bad or abandoned multipart body is a 4xx and is not logged as an error. */
  @Test
  void aTruncatedMultipartBodyIsA4xxAndNotAnErrorInTheLog() throws Exception {
    UUID claim = newClaim("1000");
    String boundary = "----cut";
    String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"documentType\"\r\n\r\nOTHER\r\n"
        + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\n\r\nabc";
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + docs(claim)))
        .header("Authorization", "Bearer " + login("claimant.sg"))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isBetween(400, 499);
    assertThat(errorEvents()).isEmpty();
    assertThat(jdbc.queryForObject("select count(*) from claim_document where claim_id = ?", Integer.class, claim)).isZero();
  }

  /** Advisory: the file is written before the commit, so a rollback must remove it. */
  @Test
  void aFailureAfterTheFileIsStoredDeletesTheFile() throws Exception {
    UUID claim = newClaim("1000");
    long before = filesOnDisk();
    org.mockito.Mockito.doThrow(new IllegalStateException("audit store down")).when(activityRepo)
        .save(org.mockito.ArgumentMatchers.<com.chubb.claims.claim.ClaimActivity>argThat(a -> a != null && "DOCUMENT_UPLOADED".equals(a.getType())));
    Resp r = upload("claimant.sg", claim, "POLICE_REPORT", "a.png", "image/png", PNG);
    assertThat(r.status()).isEqualTo(500);
    assertThat(filesOnDisk()).as("no orphan file after the rollback").isEqualTo(before);
    assertThat(jdbc.queryForObject("select count(*) from claim_document where claim_id = ?", Integer.class, claim)).isZero();
  }

  /** Advisory: the download stream is closed when the audit row cannot be written. */
  @Test
  void theDownloadStreamIsClosedWhenTheAuditSaveFails() throws Exception {
    UUID claim = newClaim("1000");
    UUID doc = uploaded(claim, "POLICE_REPORT");
    java.util.concurrent.atomic.AtomicReference<TrackedStream> opened = new java.util.concurrent.atomic.AtomicReference<>();
    org.mockito.Mockito.doAnswer(inv -> {
      opened.set(new TrackedStream((java.io.InputStream) inv.callRealMethod()));
      return opened.get();
    }).when(store).open(org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.doThrow(new IllegalStateException("audit store down")).when(activityRepo)
        .save(org.mockito.ArgumentMatchers.<com.chubb.claims.claim.ClaimActivity>argThat(a -> a != null && "DOCUMENT_DOWNLOADED".equals(a.getType())));
    assertThat(download("claimant.sg", docs(claim) + "/" + doc + "/content").statusCode()).isEqualTo(500);
    assertThat(opened.get()).isNotNull();
    assertThat(opened.get().closed).as("stream closed").isTrue();
  }

  static final class TrackedStream extends java.io.FilterInputStream {
    volatile boolean closed;

    TrackedStream(java.io.InputStream in) { super(in); }

    @Override public void close() throws IOException { closed = true; super.close(); }
  }
}
