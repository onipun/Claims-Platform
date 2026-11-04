package com.chubb.claims.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.chubb.claims.claim.StaffClaimService;
import com.chubb.claims.identity.AuthenticatedUser;
import com.chubb.claims.claim.domain.Role;
import com.chubb.claims.support.EventSchema;
import com.chubb.claims.support.KafkaTestInfra;
import com.chubb.claims.support.TestInfra;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Real Postgres, real Kafka, the real scheduled poller, real HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "app.outbox.poller.enabled=true")
@Import({TestInfra.class, KafkaTestInfra.class})
class OutboxPublishingTest extends OutboxTestSupport {
  @Autowired TransactionTemplate tx;
  @Autowired StaffClaimService staff;

  @Test
  void committedCommandsArriveOnTheTopicKeyedByClaimAndSchemaValid() {
    UUID id = newClaim("4000.00");
    ok("officer.sg", id, "pick-up", null);

    List<ConsumerRecord<String, String>> records = awaitRecords(id, 2);
    assertThat(records).extracting(ConsumerRecord::key).containsOnly(id.toString());
    for (ConsumerRecord<String, String> r : records) {
      assertThat(EventSchema.violations(r.value())).as(r.value()).isEmpty();
    }
    JsonNode submitted = parse(records.get(0).value());
    assertThat(submitted.get("eventType").asString()).isEqualTo("ClaimSubmitted");
    assertThat(submitted.get("claimVersion").asLong()).isZero();
    assertThat(submitted.get("schemaVersion").asInt()).isEqualTo(1);
    assertThat(submitted.get("snapshot").get("status").asString()).isEqualTo("SUBMITTED");
    assertThat(submitted.get("snapshot").get("reserve").isString()).as("money is a string").isTrue();

    JsonNode pickedUp = parse(records.get(1).value());
    assertThat(pickedUp.get("eventType").asString()).isEqualTo("ClaimAssigned");
    assertThat(pickedUp.get("claimVersion").asLong()).as("post-flush version, trap 1").isEqualTo(version(id));
    assertThat(pickedUp.get("snapshot").get("officerName").asString()).isEqualTo("Priya Nair");
    assertThat(pickedUp.get("snapshot").get("status").asString()).isEqualTo("UNDER_REVIEW");
    // all records of one key share a partition, in order
    assertThat(records.stream().map(ConsumerRecord::partition).distinct()).hasSize(1);

    // the outbox row is marked, so the poller will not send it again
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
        "select count(*) from outbox_event where claim_id = ? and published_at is null", Long.class, id)).isZero());
  }

  @Test
  void snapshotCarriesNoPersonalDataNoNotesAndNoFreeText() {
    UUID id = fullJourney();
    String all = String.join("\n", payloads(id));
    for (String secret : List.of("Alicia", "claimant.sg", "@demo.test", "Rear-ended", "SGX1234A", "TOP-SECRET-INTERNAL-NOTE",
        "Which workshop", "Ah Seng", "Not covered", "PR-1", "+65")) {
      assertThat(all).as("must not contain " + secret).doesNotContain(secret);
    }
    assertThat(parse(payloads(id).get(0)).get("snapshot").propertyNames()).doesNotContain(
        "description", "lossDetails", "claimantName", "claimantEmail", "contactPhone", "notes", "lateReason");
  }

  @Test
  void rollbackPublishesNothingAndLeavesNoOutboxRow() throws Exception {
    UUID id = newClaim("1500.00");
    AuthenticatedUser officer = new AuthenticatedUser(userId("officer.sg"), Role.OFFICER, Set.of("SG"), "Priya Nair");
    long versionBefore = version(id);

    try {
      tx.executeWithoutResult(s -> {
        staff.pickUp(officer, id, "\"" + versionBefore + "\"");
        throw new IllegalStateException("simulated failure after the command succeeded");
      });
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessageContaining("simulated");
    }

    assertThat(version(id)).isEqualTo(versionBefore);
    assertThat(jdbc.queryForObject("select status from claim where id = ?", String.class, id)).isEqualTo("SUBMITTED");
    assertThat(outboxCount(id)).as("only the submission event").isEqualTo(1);
    Thread.sleep(2_000); // several poll intervals
    assertThat(KafkaTestInfra.forClaim(id.toString())).as("nothing but ClaimSubmitted on the topic").hasSize(1);
  }

  /** LIFECYCLE invariants 7 and 8, checked in the database after a whole journey with failures mixed in. */
  @Test
  void everySuccessfulCommandWritesExactlyOneConsecutiveVersionAndFailuresWriteNone() {
    UUID id = newClaim("4000.00");
    long rows = outboxCount(id);
    assertThat(rows).isEqualTo(1);

    // failures, each of which must leave the outbox untouched
    // LIFECYCLE order: access (404) is checked before If-Match (428), so an officer who is not on the claim learns nothing
    assertThat(cmd("officer.sg", id, "notes", Map.of("text", "x"), null).status()).as("unassigned, no If-Match: access first").isEqualTo(404);
    assertThat(cmd("officer.sg", id, "pick-up", null, "\"99\"").status()).as("stale").isEqualTo(409);
    assertThat(cmd("officer.sg", id, "settlement", Map.of("amount", "1"), current(id)).status())
        .as("not the assigned officer: hidden").isEqualTo(404);
    assertThat(outboxCount(id)).isEqualTo(rows);

    ok("officer.sg", id, "pick-up", null);
    assertThat(cmd("officer.sg", id, "notes", Map.of("text", "x"), null).status()).as("assigned, no If-Match").isEqualTo(428);
    assertThat(cmd("officer.sg", id, "pick-up", null, current(id)).status()).as("already assigned: blank cell").isEqualTo(409);
    assertThat(cmd("officer.sg", id, "approval", Map.of("amount", "1"), current(id)).status())
        .as("illegal transition").isEqualTo(409);
    assertThat(cmd("officer.sg", id, "assessment", Map.of("reserve", "-5"), current(id)).status())
        .as("invalid amount").isEqualTo(422);
    ok("officer.sg", id, "notes", Map.of("text", "n"));
    ok("officer.sg", id, "info-requests", Map.of("question", "q"));
    assertThat(cmd("officer.sg", id, "assessment", Map.of("reserve", "1"), current(id)).status())
        .as("assess while awaiting info").isEqualTo(409);
    answer(id, "a");
    ok("officer.sg", id, "assessment", Map.of("reserve", "3800"));
    ok("manager.sg", id, "approval", Map.of("amount", "3500.00"));
    ok("officer.sg", id, "settlement", Map.of("amount", "3500.00"));
    assertThat(cmd("officer.sg", id, "notes", Map.of("text", "after terminal"), current(id)).status()).isEqualTo(409);

    // 8 successful commands: submit, pick-up, note, request, answer, assess, approve, settle
    List<Long> versions = jdbc.queryForList("select version from outbox_event where claim_id = ? order by version",
        Long.class, id);
    assertThat(versions).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L);
    assertThat(version(id)).as("claim version equals the last event version").isEqualTo(7L);
    assertThat(jdbc.queryForList("select event_type from outbox_event where claim_id = ? order by version",
        String.class, id)).containsExactly("ClaimSubmitted", "ClaimAssigned", "ClaimUpdated",
        "ClaimStatusChanged", "ClaimStatusChanged", "ClaimAssessed", "ClaimApproved", "ClaimSettled");
    assertThat(jdbc.queryForObject("select count(distinct event_id) from outbox_event where claim_id = ?", Long.class, id))
        .isEqualTo(8);
    for (String payload : payloads(id)) assertThat(EventSchema.violations(payload)).as(payload).isEmpty();
    // payload version equals the column version for every row
    assertThat(jdbc.queryForObject("select count(*) from outbox_event where claim_id = ? "
        + "and (payload->>'claimVersion')::bigint <> version", Long.class, id)).isZero();

    // and the topic received all eight, in order
    List<ConsumerRecord<String, String>> got = awaitRecords(id, 8);
    assertThat(got.stream().map(r -> parse(r.value()).get("claimVersion").asLong()).toList())
        .containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L);
  }

  /** With -Dcontract.update=true, rewrites contracts/events/examples from a real run (one file per event type). */
  @Test
  void everyEventTypeHasARealExample() throws IOException {
    UUID full = fullJourney();
    UUID rejected = rejectedJourney();
    Map<String, String> byType = new TreeMap<>();
    for (UUID id : List.of(full, rejected)) {
      for (String p : payloads(id)) byType.putIfAbsent(parse(p).get("eventType").asString(), p);
    }
    assertThat(byType.keySet()).containsExactlyInAnyOrder(
        java.util.Arrays.stream(ClaimEventType.values()).map(Enum::name).toArray(String[]::new));
    for (String json : byType.values()) assertThat(EventSchema.violations(json)).isEmpty();
    if (Boolean.getBoolean("contract.update")) {
      Path dir = EventSchema.DIR.resolve("examples");
      Files.createDirectories(dir);
      for (Map.Entry<String, String> e : byType.entrySet()) {
        Files.writeString(dir.resolve(e.getKey() + ".json"),
            JSON.writerWithDefaultPrettyPrinter().writeValueAsString(parse(e.getValue())) + "\n");
      }
    }
  }

  private List<ConsumerRecord<String, String>> awaitRecords(UUID id, int expected) {
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
      found.clear();
      found.addAll(KafkaTestInfra.forClaim(id.toString()));
      assertThat(found).hasSizeGreaterThanOrEqualTo(expected);
    });
    return found;
  }
}
