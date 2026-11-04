package com.chubb.claims.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.chubb.claims.claim.domain.Command;
import com.chubb.claims.support.StaffApiTest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * R5, R6: real concurrency through the API. The claim row lock serialises document writes per claim
 * and against commands. A smaller cap keeps the cap test small; the code reads it from
 * {@code app.documents.max-per-claim} (default 50).
 */
@TestPropertySource(properties = "app.documents.max-per-claim=5")
class DocumentConcurrencyTest extends StaffApiTest {
  @Autowired @MockitoSpyBean DocumentStore store;

  private static final byte[] PNG = Base64.getDecoder().decode(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private int upload(UUID claim, String type) {
    String b = "----c" + UUID.randomUUID();
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"documentType\"\r\n\r\n" + type + "\r\n"
          + "--" + b + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"p.png\"\r\n"
          + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
      out.write(PNG);
      out.write(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
      HttpResponse<String> r = HTTP.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + CLAIMANT_CLAIMS + "/" + claim + "/documents"))
              .header("Authorization", "Bearer " + login("claimant.sg"))
              .header("Content-Type", "multipart/form-data; boundary=" + b)
              .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),
          HttpResponse.BodyHandlers.ofString());
      return r.statusCode();
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID uploadedDoc(UUID claim) {
    java.util.Set<UUID> before = new java.util.HashSet<>(
        jdbc.queryForList("select id from claim_document where claim_id = ?", UUID.class, claim));
    assertThat(upload(claim, "OTHER")).isEqualTo(201);
    return jdbc.queryForList("select id from claim_document where claim_id = ?", UUID.class, claim).stream()
        .filter(id -> !before.contains(id)).findFirst().orElseThrow();
  }

  private <T> List<T> race(List<Callable<T>> tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    CyclicBarrier gate = new CyclicBarrier(tasks.size());
    try {
      List<Future<T>> fs = new ArrayList<>();
      for (Callable<T> t : tasks) fs.add(pool.submit(() -> { gate.await(); return t.call(); }));
      List<T> out = new ArrayList<>();
      for (Future<T> f : fs) out.add(f.get());
      return out;
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void twoReviewsOfTheSameDocumentGiveOneWinnerAndOneActivity() throws Exception {
    UUID claim = newClaim("1000");
    staffSide.run(claim, Command.pickUp(officerSg()));
    for (int round = 0; round < 4; round++) {
      UUID doc = uploadedDoc(claim);
      String review = STAFF_CLAIMS + claim + "/documents/" + doc + "/review";
      List<Resp> rs = race(List.<Callable<Resp>>of(
          () -> post(review, login("officer.sg"), "{\"decision\":\"ACCEPTED\"}"),
          () -> post(review, login("manager.sg"), "{\"decision\":\"RE_REQUESTED\",\"reason\":\"sharper please\"}")));
      List<Integer> codes = rs.stream().map(Resp::status).sorted().toList();
      assertThat(codes).as("round %d: %s | %s", round, rs.get(0).body(), rs.get(1).body()).containsExactly(200, 409);
      Resp loser = rs.stream().filter(r -> r.status() == 409).findFirst().orElseThrow();
      assertThat(loser.json().get("code").asString()).isIn("DOCUMENT_NOT_REVIEWABLE", "STALE_VERSION");
      int reviewActivities = jdbc.queryForObject(
          "select count(*) from claim_activity where claim_id = ? and activity_type in ('DOCUMENT_ACCEPTED','DOCUMENT_REREQUESTED') "
              + "and message like '%' || (select document_type from claim_document where id = ?) || '%'",
          Integer.class, claim, doc);
      long reviewed = jdbc.queryForObject("select count(*) from claim_document where id = ? and reviewed_at is not null",
          Long.class, doc);
      assertThat(reviewed).isEqualTo(1);
      // every earlier round also left exactly one activity, so the total is the round count so far
      assertThat(reviewActivities).as("round %d", round).isEqualTo(round + 1);
    }
  }

  @Test
  void uploadsAndReviewsMixedWithCommandsNeverDeadlockOrFail() throws Exception {
    UUID claim = newClaim("1000");
    staffSide.run(claim, Command.pickUp(officerSg()));
    UUID doc = uploadedDoc(claim);
    String review = STAFF_CLAIMS + claim + "/documents/" + doc + "/review";
    String ifMatch = current(claim);
    List<Integer> codes = race(List.<Callable<Integer>>of(
        () -> post(review, login("officer.sg"), "{\"decision\":\"ACCEPTED\"}").status(),
        () -> upload(claim, "OTHER"),
        () -> upload(claim, "OTHER"),
        () -> cmd("officer.sg", claim, "notes", Map.of("text", "n"), ifMatch).status(),
        () -> cmd("manager.sg", claim, "notes", Map.of("text", "m"), ifMatch).status()));
    assertThat(codes).as("%s", codes).allMatch(c -> c == 200 || c == 201 || c == 409);
    assertThat(jdbc.queryForObject("select count(*) from pg_locks where not granted", Integer.class)).isZero();
  }

  @Test
  void parallelUploadsNeverExceedTheCap() throws Exception {
    UUID claim = newClaim("1000");
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 14; i++) tasks.add(() -> upload(claim, "OTHER"));
    List<Integer> codes = race(tasks);
    long created = codes.stream().filter(c -> c == 201).count();
    assertThat(created).as("%s", codes).isEqualTo(5);
    assertThat(codes.stream().filter(c -> c == 409).count()).isEqualTo(9);
    assertThat(jdbc.queryForObject("select count(*) from claim_document where claim_id = ?", Integer.class, claim))
        .isEqualTo(5);
  }

  /**
   * The settle starts while the upload is between its status check and its commit (the spy holds the
   * upload there for 700 ms). With the claim lock the settle cannot finish in that window: it waits and
   * lands after the document, so the document is on a claim that was still open. Without the lock the
   * settle commits inside the window and the document then lands on a settled claim.
   */
  @Test
  void anUploadRacingASettleNeverLeavesADocumentOnAnAlreadySettledClaim() throws Exception {
    UUID claim = newClaim("1000");
    ok("officer.sg", claim, "pick-up", null);
    ok("officer.sg", claim, "assessment", Map.of("reserve", "800"));
    ok("officer.sg", claim, "approval", Map.of("amount", "800"));
    String ifMatch = current(claim);
    @SuppressWarnings("unchecked") CompletableFuture<Integer>[] settle = new CompletableFuture[1];
    java.util.concurrent.atomic.AtomicBoolean settledInsideTheWindow = new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(inv -> {
      settle[0] = CompletableFuture.supplyAsync(
          () -> cmd("officer.sg", claim, "settlement", Map.of("amount", "800"), ifMatch).status());
      Thread.sleep(700);
      settledInsideTheWindow.set("SETTLED".equals(
          jdbc.queryForObject("select status from claim where id = ?", String.class, claim)));
      return inv.callRealMethod();
    }).when(store).save(any(), any());

    int up = upload(claim, "OTHER");
    assertThat(settle[0].get()).isEqualTo(200);
    assertThat(settledInsideTheWindow).as("the settle must wait for the in-flight upload").isFalse();
    assertThat(up).isEqualTo(201);
    assertThat(jdbc.queryForObject("select count(*) from claim_document where claim_id = ?", Long.class, claim))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select status from claim where id = ?", String.class, claim)).isEqualTo("SETTLED");
    // and once it is settled, a later upload is refused
    assertThat(upload(claim, "OTHER")).isEqualTo(409);
  }
}
