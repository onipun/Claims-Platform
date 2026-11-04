package com.chubb.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.chubb.insights.projection.ClaimEventConsumer;
import com.chubb.insights.support.EventSchema;
import com.chubb.insights.support.InsightsTestBase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real Postgres and Kafka. Fixtures are contracts/events/examples/*.json, checked against the schema first. */
@ExtendWith(OutputCaptureExtension.class)
class ClaimEventConsumerTest extends InsightsTestBase {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Path EXAMPLES = EventSchema.DIR.resolve("examples");
  private static final String GROUP = "insights-projection";

  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired ConcurrentKafkaListenerContainerFactory<String, String> factory;
  @Autowired ClaimEventConsumer consumer;

  // ---- helpers ----

  private static String example(String name) throws Exception {
    return Files.readString(EXAMPLES.resolve(name + ".json"));
  }

  /** The example with its claim re-identified, so each test uses its own claim. */
  private static ObjectNode forClaim(String name, UUID claim, Long version) throws Exception {
    ObjectNode e = (ObjectNode) JSON.readTree(example(name));
    e.put("claimId", claim.toString());
    e.put("eventId", UUID.randomUUID().toString());
    ((ObjectNode) e.get("snapshot")).put("claimId", claim.toString());
    if (version != null) e.put("claimVersion", version);
    return e;
  }

  private static final List<String> JOURNEY = List.of("ClaimSubmitted", "ClaimAssigned", "ClaimUpdated",
      "ClaimStatusChanged", "ClaimAssessed", "ClaimApproved", "ClaimSettled");

  private int send(UUID key, String value) throws Exception {
    return kafka.send(TOPIC, key.toString(), value).get().getRecordMetadata().partition();
  }

  private void sendNode(UUID claim, JsonNode e) throws Exception {
    send(claim, JSON.writeValueAsString(e));
  }

  private Map<String, Object> row(UUID claim) {
    var rows = jdbc.queryForList("SELECT * FROM claim_projection WHERE claim_id = ?", claim);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** The group has committed every offset of the topic: everything sent so far was processed and acked. */
  private void awaitCaughtUp() {
    await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
      try (AdminClient admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()));
           KafkaConsumer<String, String> c = plainConsumer(UUID.randomUUID().toString())) {
        List<TopicPartition> parts = c.partitionsFor(TOPIC).stream().map(i -> new TopicPartition(TOPIC, i.partition())).toList();
        Map<TopicPartition, Long> end = c.endOffsets(parts);
        Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get();
        for (TopicPartition tp : parts) {
          long done = committed.containsKey(tp) ? committed.get(tp).offset() : 0;
          assertThat(done).as("committed offset of " + tp).isEqualTo(end.get(tp));
        }
      }
    });
  }

  private static KafkaConsumer<String, String> plainConsumer(String group) {
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    p.put(ConsumerConfig.GROUP_ID_CONFIG, group);
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    return new KafkaConsumer<>(p);
  }

  private static List<ConsumerRecord<String, String>> dltFor(UUID key) {
    List<ConsumerRecord<String, String>> out = new ArrayList<>();
    try (KafkaConsumer<String, String> c = plainConsumer(UUID.randomUUID().toString())) {
      List<TopicPartition> parts = c.partitionsFor(DLT).stream().map(i -> new TopicPartition(DLT, i.partition())).toList();
      c.assign(parts);
      c.seekToBeginning(parts);
      Map<TopicPartition, Long> end = c.endOffsets(parts);
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (System.nanoTime() < deadline && parts.stream().anyMatch(tp -> c.position(tp) < end.get(tp))) {
        c.poll(Duration.ofMillis(200)).forEach(r -> { if (key.toString().equals(r.key())) out.add(r); });
      }
    }
    return out;
  }

  private List<ConsumerRecord<String, String>> awaitDlt(UUID key) {
    return await().atMost(Duration.ofSeconds(30)).until(() -> dltFor(key), l -> !l.isEmpty());
  }

  // ---- tests ----

  @Test
  void everyExampleIsValidAgainstTheSchema() throws Exception {
    try (var files = Files.list(EXAMPLES)) {
      for (Path f : files.toList()) {
        assertThat(EventSchema.violations(Files.readString(f))).as(f.getFileName().toString()).isEmpty();
      }
    }
  }

  @Test
  void newEventInserts() throws Exception {
    UUID id = UUID.randomUUID();
    ObjectNode e = forClaim("ClaimSubmitted", id, null);
    assertThat(EventSchema.violations(JSON.writeValueAsString(e))).isEmpty();
    sendNode(id, e);
    await().atMost(Duration.ofSeconds(30)).until(() -> row(id) != null);
    Map<String, Object> r = row(id);
    assertThat(r.get("status")).isEqualTo("SUBMITTED");
    assertThat(r.get("market").toString().trim()).isEqualTo("SG");
    assertThat(r.get("currency").toString().trim()).isEqualTo("SGD");
    assertThat(r.get("reserve")).hasToString("3500.0000");
    assertThat(r.get("reserve_status")).isEqualTo("PROVISIONAL");
    assertThat(((Number) r.get("version")).longValue()).isZero();
    assertThat(r.get("officer_id")).isNull();
  }

  @Test
  void duplicateChangesNothing() throws Exception {
    UUID id = UUID.randomUUID();
    String json = JSON.writeValueAsString(forClaim("ClaimAssigned", id, null));
    send(id, json);
    await().atMost(Duration.ofSeconds(30)).until(() -> row(id) != null);
    Map<String, Object> before = row(id);
    send(id, json);
    send(id, json);
    awaitCaughtUp();
    assertThat(row(id)).isEqualTo(before);
  }

  @Test
  void olderVersionAfterNewerChangesNothing() throws Exception {
    UUID id = UUID.randomUUID();
    sendNode(id, forClaim("ClaimSettled", id, null)); // v7
    await().atMost(Duration.ofSeconds(30)).until(() -> row(id) != null);
    Map<String, Object> before = row(id);
    sendNode(id, forClaim("ClaimStatusChanged", id, null)); // v3
    sendNode(id, forClaim("ClaimSubmitted", id, null)); // v0
    awaitCaughtUp();
    assertThat(row(id)).isEqualTo(before);
    assertThat(row(id).get("status")).isEqualTo("SETTLED");
  }

  @Test
  void outOfOrderJourneyShuffledWithFixedSeedEndsWhereInOrderEnds() throws Exception {
    UUID id = UUID.randomUUID();
    for (String name : JOURNEY) sendNode(id, forClaim(name, id, null));
    await().atMost(Duration.ofSeconds(30)).until(() -> row(id) != null && ((Number) row(id).get("version")).longValue() == 7);
    awaitCaughtUp();
    Map<String, Object> inOrder = row(id);
    assertThat(inOrder.get("status")).isEqualTo("SETTLED");

    jdbc.update("TRUNCATE claim_projection");
    List<String> shuffled = new ArrayList<>(JOURNEY);
    Collections.shuffle(shuffled, new Random(42));
    assertThat(shuffled).as("the fixed seed really reorders").isNotEqualTo(JOURNEY);
    UUID again = UUID.randomUUID();
    for (String name : shuffled) sendNode(again, forClaim(name, again, null));
    awaitCaughtUp();
    Map<String, Object> got = new HashMap<>(row(again));
    Map<String, Object> want = new HashMap<>(inOrder);
    got.remove("claim_id");
    want.remove("claim_id");
    assertThat(got).isEqualTo(want);
  }

  @Test
  void poisonGoesToTheDltOnTheSamePartitionAndTheNextGoodEventStillApplies(CapturedOutput output) throws Exception {
    UUID id = UUID.randomUUID();
    int partition = send(id, "{\"secret\": \"PAYLOAD-MARKER-9f3\", not json");
    int p2 = send(id, JSON.writeValueAsString(forClaim("ClaimSubmitted", id, null)));
    assertThat(p2).as("same key, same partition").isEqualTo(partition);
    var dlt = awaitDlt(id);
    assertThat(dlt).hasSize(1);
    assertThat(dlt.get(0).partition()).as("DLT record is on the source partition").isEqualTo(partition);
    assertThat(dlt.get(0).value()).contains("PAYLOAD-MARKER-9f3"); // forensic copy lives in the DLT only
    await().atMost(Duration.ofSeconds(30)).until(() -> row(id) != null);
    assertThat(row(id).get("status")).isEqualTo("SUBMITTED");
    assertThat(output.getAll()).as("logs carry ids only, never payloads").doesNotContain("PAYLOAD-MARKER-9f3");
    assertThat(output.getAll()).as("the DLT hand-off has its own line, with the claim id").contains("EVENT_DEAD_LETTERED claimId=" + id)
        .contains("claimVersion=unknown").contains("reason=PoisonEventException");
  }

  @Test
  void missingRequiredFieldIsPoisonToo(CapturedOutput output) throws Exception {
    UUID id = UUID.randomUUID();
    ObjectNode e = forClaim("ClaimSubmitted", id, null);
    ((ObjectNode) e.get("snapshot")).remove("market");
    sendNode(id, e);
    assertThat(awaitDlt(id)).hasSize(1);
    assertThat(row(id)).isNull();
    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
        assertThat(output.getAll()).containsPattern("EVENT_DEAD_LETTERED claimId=" + id + " claimVersion=\\d+ "));
  }

  @Test
  void unknownFieldsAreIgnored() throws Exception {
    UUID id = UUID.randomUUID();
    ObjectNode e = forClaim("ClaimAssessed", id, null);
    e.put("addedByNewerProducer", "x");
    ((ObjectNode) e.get("snapshot")).put("newSnapshotField", 42);
    assertThat(EventSchema.violations(JSON.writeValueAsString(e))).as("the v1 contract itself forbids it").isNotEmpty();
    sendNode(id, e);
    await().atMost(Duration.ofSeconds(30)).until(() -> row(id) != null);
    assertThat(row(id).get("status")).isEqualTo("ASSESSED");
    assertThat(dltFor(id)).isEmpty();
  }

  @Test
  void unsupportedMajorSchemaVersionGoesToTheDlt() throws Exception {
    UUID id = UUID.randomUUID();
    ObjectNode e = forClaim("ClaimSubmitted", id, null);
    e.put("schemaVersion", 2);
    sendNode(id, e);
    assertThat(awaitDlt(id)).hasSize(1);
    awaitCaughtUp();
    assertThat(row(id)).isNull();
  }

  /** Postgres paused through the Docker API: a good event must wait, not be dead-lettered, and apply on recovery. */
  @Test
  void databaseOutageRetriesTheEventAndNeverDeadLettersIt() throws Exception {
    awaitCaughtUp();
    UUID id = UUID.randomUUID();
    var docker = POSTGRES.getDockerClient();
    docker.pauseContainerCmd(POSTGRES.getContainerId()).exec();
    try {
      sendNode(id, forClaim("ClaimSubmitted", id, null));
      Thread.sleep(8_000); // several retry rounds at 200ms..1s backoff, well past max-attempts
      assertThat(dltFor(id)).as("good event is not dead-lettered while Postgres is down").isEmpty();
    } finally {
      docker.unpauseContainerCmd(POSTGRES.getContainerId()).exec();
    }
    await().atMost(Duration.ofSeconds(60)).until(() -> row(id) != null);
    assertThat(row(id).get("status")).isEqualTo("SUBMITTED");
    assertThat(dltFor(id)).as("still not dead-lettered after recovery").isEmpty();
  }

  /** New consumer group, emptied projection: replaying the topic from the start reproduces the same rows. */
  @Test
  void replayingTheTopicWithANewGroupRebuildsTheSameProjection() throws Exception {
    List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    List<String> order = new ArrayList<>(JOURNEY);
    Collections.shuffle(order, new Random(7));
    for (UUID id : ids) for (String name : order) sendNode(id, forClaim(name, id, null));
    awaitCaughtUp();
    Map<UUID, Map<String, Object>> live = new HashMap<>();
    for (UUID id : ids) live.put(id, row(id));
    assertThat(live.values()).allSatisfy(r -> assertThat(((Number) r.get("version")).longValue()).isEqualTo(7));

    jdbc.update("TRUNCATE claim_projection");
    ConcurrentMessageListenerContainer<String, String> rebuild = factory.createContainer(TOPIC);
    rebuild.getContainerProperties().setGroupId("rebuild-" + UUID.randomUUID());
    rebuild.getContainerProperties().setMessageListener((AcknowledgingMessageListener<String, String>) consumer::onRecord);
    rebuild.start();
    try {
      await().atMost(Duration.ofSeconds(60)).until(() -> ids.stream().allMatch(i -> row(i) != null
          && ((Number) row(i).get("version")).longValue() == 7));
    } finally {
      rebuild.stop();
    }
    for (UUID id : ids) assertThat(row(id)).isEqualTo(live.get(id));
  }
}
