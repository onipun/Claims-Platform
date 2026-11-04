package com.chubb.claims.claim;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.claims.support.StaffApiTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * I8: after every command type exactly one new claim_activity row appears, with the right type, actor,
 * from and to status, visibility and a non-null time. Real HTTP, real database.
 */
class ClaimActivityJourneyTest extends StaffApiTest {

  private int rows(UUID id) {
    return jdbc.queryForObject("select count(*) from claim_activity where claim_id = ?", Integer.class, id);
  }

  private void expectOneNewRow(UUID id, int before, String type, String actorEmail, String from, String to,
      String visibility) {
    assertThat(rows(id)).as(type + ": exactly one new row").isEqualTo(before + 1);
    Map<String, Object> last = jdbc.queryForMap(
        "select activity_type, actor_id, from_status, to_status, visibility, created_at "
            + "from claim_activity where claim_id = ? order by id desc limit 1", id);
    assertThat(last.get("activity_type")).as(type).isEqualTo(type);
    assertThat(last.get("actor_id")).as(type + " actor").isEqualTo(userId(actorEmail));
    assertThat(last.get("from_status")).as(type + " from").isEqualTo(from);
    assertThat(last.get("to_status")).as(type + " to").isEqualTo(to);
    assertThat(last.get("visibility")).as(type + " visibility").isEqualTo(visibility);
    assertThat(last.get("created_at")).as(type + " time").isNotNull();
  }

  @Test
  void everyCommandTypeWritesExactlyOneActivityRow() {
    UUID id = newClaim("4000");
    assertThat(rows(id)).isEqualTo(1);
    int n = 1;

    ok("officer.sg", id, "pick-up", null);
    expectOneNewRow(id, n++, "PICK_UP", "officer.sg", "SUBMITTED", "UNDER_REVIEW", "INTERNAL");

    ok("manager.sg", id, "assign", Map.of("officerId", userId("officer2.sg").toString()));
    expectOneNewRow(id, n++, "ASSIGN", "manager.sg", "UNDER_REVIEW", "UNDER_REVIEW", "INTERNAL");

    ok("manager.sg", id, "notes", Map.of("text", "internal"));
    expectOneNewRow(id, n++, "ADD_NOTE", "manager.sg", "UNDER_REVIEW", "UNDER_REVIEW", "INTERNAL");

    ok("manager.sg", id, "info-requests", Map.of("question", "Which workshop?"));
    expectOneNewRow(id, n++, "REQUEST_INFO", "manager.sg", "UNDER_REVIEW", "AWAITING_INFO", "CLAIMANT");

    Resp r = post(CLAIMANT_CLAIMS + "/" + id + "/info-responses", login("claimant.sg"),
        toJson(Map.of("answer", "Ah Seng")), "If-Match", current(id));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    expectOneNewRow(id, n++, "ANSWER_INFO", "claimant.sg", "AWAITING_INFO", "UNDER_REVIEW", "CLAIMANT");

    ok("manager.sg", id, "assessment", Map.of("reserve", "3800"));
    expectOneNewRow(id, n++, "ASSESS", "manager.sg", "UNDER_REVIEW", "ASSESSED", "INTERNAL");

    ok("manager.sg", id, "approval", Map.of("amount", "3500"));
    expectOneNewRow(id, n++, "APPROVE", "manager.sg", "ASSESSED", "APPROVED", "CLAIMANT");

    ok("manager.sg", id, "settlement", Map.of("amount", "3500"));
    expectOneNewRow(id, n, "SETTLE", "manager.sg", "APPROVED", "SETTLED", "CLAIMANT");

    // a failed command writes no row
    int before = rows(id);
    assertThat(cmd("manager.sg", id, "notes", Map.of("text", "late"), current(id)).status()).isEqualTo(409);
    assertThat(rows(id)).isEqualTo(before);
  }

  @Test
  void rejectWritesExactlyOneActivityRow() {
    UUID id = newClaim("2000");
    ok("officer.sg", id, "pick-up", null);
    int before = rows(id);
    ok("officer.sg", id, "rejection", Map.of("reason", "NOT_COVERED", "explanation", "Excluded cause"));
    expectOneNewRow(id, before, "REJECT", "officer.sg", "UNDER_REVIEW", "REJECTED", "CLAIMANT");
  }
}
