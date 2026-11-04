package com.chubb.claims.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;

import com.chubb.claims.support.ApiTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Trap 7, deterministically: the pre-check is made to miss an existing claim, so the insert hits
 * the unique index. The service must answer with the original, not a 500.
 */
class IdempotencyRaceTest extends ApiTest {
  @MockitoSpyBean ClaimSubmissionTx tx;
  @Autowired ClaimRepository claims;

  @Test
  void losingTheRaceReturnsTheOriginalNotA500() {
    String k = key();
    String token = login("claimant.sg");
    String body = toJson(motorBody(policyId("MOT-SG-100001"), "2026-03-10T09:00:00", "900"));
    Resp first = post("/api/v1/claimant/claims", token, body, "Idempotency-Key", k);
    assertThat(first.status()).isEqualTo(201);

    // The next pre-check misses (as if the other request had not committed yet); the retry sees it.
    doReturn(Optional.empty()).doCallRealMethod().when(tx).findExisting(any(), any(), any());
    Resp loser = post("/api/v1/claimant/claims", token, body, "Idempotency-Key", k);

    assertThat(loser.status()).as(loser.body()).isEqualTo(200);
    assertThat(loser.json().get("id").asString()).isEqualTo(first.json().get("id").asString());
    assertThat(jdbc.queryForObject("select count(*) from claim where idempotency_key = ?", Integer.class, k))
        .isEqualTo(1);
    // The failed attempt took a claim number from the sequence but left no rows behind.
    assertThat(jdbc.queryForObject("select count(*) from financial_entry f join claim c on c.id = f.claim_id "
        + "where c.idempotency_key = ?", Integer.class, k)).isEqualTo(1);
  }
}
