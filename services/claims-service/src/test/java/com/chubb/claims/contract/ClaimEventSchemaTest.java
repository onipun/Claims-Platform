package com.chubb.claims.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.claims.outbox.ClaimEventType;
import com.chubb.claims.support.EventSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Trap 12: a field cannot reach Kafka by accident. The examples are real outbox payloads (written by
 * OutboxPublishingTest with -Dcontract.update=true); insights tests read the same files.
 */
class ClaimEventSchemaTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Path EXAMPLES = EventSchema.DIR.resolve("examples");

  private static ObjectNode example(String type) throws IOException {
    return (ObjectNode) JSON.readTree(Files.readString(EXAMPLES.resolve(type + ".json")));
  }

  @Test
  void thereIsOneValidExampleForEveryEventType() throws IOException {
    try (Stream<Path> files = Files.list(EXAMPLES)) {
      assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactlyInAnyOrderElementsOf(
          java.util.Arrays.stream(ClaimEventType.values()).map(t -> t.name() + ".json").toList());
    }
    for (ClaimEventType t : ClaimEventType.values()) {
      ObjectNode e = example(t.name());
      assertThat(e.get("eventType").asString()).isEqualTo(t.name());
      assertThat(EventSchema.violations(e.toString())).as(t.name()).isEmpty();
    }
  }

  @Test
  void anExtraEnvelopeFieldFailsValidation() throws IOException {
    ObjectNode e = example("ClaimApproved");
    e.put("claimantEmail", "alicia@example.com");
    List<String> v = EventSchema.violations(e.toString());
    assertThat(v).isNotEmpty();
    assertThat(String.join("\n", v)).contains("claimantEmail");
  }

  @Test
  void anExtraSnapshotFieldFailsValidation() throws IOException {
    ObjectNode e = example("ClaimApproved");
    ((ObjectNode) e.get("snapshot")).put("description", "Rear-ended at a junction");
    List<String> v = EventSchema.violations(e.toString());
    assertThat(v).isNotEmpty();
    assertThat(String.join("\n", v)).contains("description");
  }

  @Test
  void aMissingFieldMoneyAsNumberAndBadEnumFailValidation() throws IOException {
    ObjectNode noVersion = example("ClaimSubmitted");
    noVersion.remove("claimVersion");
    assertThat(EventSchema.violations(noVersion.toString())).isNotEmpty();

    ObjectNode number = example("ClaimSubmitted");
    ((ObjectNode) number.get("snapshot")).put("reserve", 3500.0);
    assertThat(EventSchema.violations(number.toString())).as("money must be a string").isNotEmpty();

    ObjectNode badType = example("ClaimSubmitted");
    badType.put("eventType", "ClaimExploded");
    assertThat(EventSchema.violations(badType.toString())).isNotEmpty();

    ObjectNode badId = example("ClaimSubmitted");
    badId.put("claimId", "not-a-uuid");
    assertThat(EventSchema.violations(badId.toString())).as("format uuid is asserted").isNotEmpty();
  }

  @Test
  void theSnapshotRecordAndTheSchemaListTheSameFields() throws IOException {
    JsonNode schema = JSON.readTree(Files.readString(EventSchema.DIR.resolve("claim-event.v1.schema.json")));
    List<String> inSchema = schema.at("/$defs/ClaimSnapshot/properties").propertyNames().stream().sorted().toList();
    List<String> inCode = java.util.Arrays.stream(com.chubb.claims.outbox.ClaimEventSnapshot.class.getRecordComponents())
        .map(java.lang.reflect.RecordComponent::getName).sorted().toList();
    assertThat(inCode).isEqualTo(inSchema);
  }
}
