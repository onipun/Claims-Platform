package com.chubb.claims.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.claims.support.StaffApiTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Shared journeys for the outbox tests. All traffic is the real HTTP API. */
abstract class OutboxTestSupport extends StaffApiTest {

  /** submit, pick-up, note, info request, answer, assess, approve, settle: 8 successful commands (v0..v7). */
  protected UUID fullJourney() {
    UUID id = newClaim("4000.00");
    ok("officer.sg", id, "pick-up", null);
    ok("officer.sg", id, "notes", Map.of("text", "TOP-SECRET-INTERNAL-NOTE"));
    ok("officer.sg", id, "info-requests", Map.of("question", "Which workshop?"));
    answer(id, "Ah Seng Auto");
    ok("officer.sg", id, "assessment", Map.of("reserve", "3800"));
    ok("manager.sg", id, "approval", Map.of("amount", "3500.00"));
    ok("officer.sg", id, "settlement", Map.of("amount", "3500.00"));
    return id;
  }

  protected UUID rejectedJourney() {
    UUID id = newClaim("900.00");
    ok("officer.sg", id, "pick-up", null);
    ok("officer.sg", id, "rejection", Map.of("reason", "NOT_COVERED", "explanation", "Not covered, free text stays home"));
    return id;
  }

  protected void answer(UUID id, String text) {
    Resp r = post(CLAIMANT_CLAIMS + "/" + id + "/info-responses", login("claimant.sg"),
        toJson(Map.of("answer", text)), "If-Match", current(id));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
  }

  protected long outboxCount(UUID claim) {
    return jdbc.queryForObject("select count(*) from outbox_event where claim_id = ?", Long.class, claim);
  }

  protected List<String> payloads(UUID claim) {
    return jdbc.queryForList("select payload::text from outbox_event where claim_id = ? order by version",
        String.class, claim);
  }

  protected static JsonNode parse(String json) {
    return JSON.readTree(json);
  }
}
