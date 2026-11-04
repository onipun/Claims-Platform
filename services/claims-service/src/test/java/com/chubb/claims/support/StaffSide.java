package com.chubb.claims.support;

import com.chubb.claims.claim.Claim;
import com.chubb.claims.claim.ClaimRepository;
import com.chubb.claims.claim.domain.Actor;
import com.chubb.claims.claim.domain.Command;
import com.chubb.claims.claim.domain.Role;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/** Stands in for the staff API (Task 6): applies staff commands through the domain, directly. */
public class StaffSide {
  private final ClaimRepository claims;
  private final TransactionTemplate tx;
  private final MutableClock clock;

  public StaffSide(ClaimRepository claims, TransactionTemplate tx, MutableClock clock) {
    this.claims = claims;
    this.tx = tx;
    this.clock = clock;
  }

  public Actor officer(UUID id, String market) {
    return new Actor(id, Role.OFFICER, Set.of(market), new BigDecimal("10000"));
  }

  public void run(UUID claimId, Command... commands) {
    tx.executeWithoutResult(s -> {
      Claim c = claims.findById(claimId).orElseThrow();
      // Flush per command, as the staff API will: each command bumps the version by exactly one.
      for (Command cmd : commands) {
        c.execute(cmd, clock.instant());
        claims.saveAndFlush(c);
      }
    });
  }
}
