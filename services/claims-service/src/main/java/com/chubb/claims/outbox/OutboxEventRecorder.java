package com.chubb.claims.outbox;

import com.chubb.claims.claim.Claim;
import com.chubb.claims.claim.ClaimEventRecorder;
import com.chubb.claims.reference.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the outbox row in the command's own transaction (D3), so a rollback leaves no event and a
 * commit always leaves one. MANDATORY: calling it outside a transaction is a bug, not a no-op.
 * Called after saveAndFlush, so {@code claim.getVersion()} is the new version (trap 1).
 */
@Component
class OutboxEventRecorder implements ClaimEventRecorder {
  private final OutboxRepository outbox;
  private final AppUserRepository users;
  private final JsonMapper json;
  private final Clock clock;

  OutboxEventRecorder(OutboxRepository outbox, AppUserRepository users, JsonMapper json, Clock clock) {
    this.outbox = outbox;
    this.users = users;
    this.json = json;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void record(Claim claim, String commandEvent) {
    ClaimEventType type = ClaimEventType.fromCommand(commandEvent);
    String officerName = claim.getAssignedOfficerId() == null ? null
        : users.findById(claim.getAssignedOfficerId()).map(u -> u.getName()).orElse(null);
    Instant now = clock.instant();
    UUID eventId = UUID.randomUUID();
    long version = claim.getVersion();
    ClaimEventEnvelope envelope = new ClaimEventEnvelope(eventId, type.name(), ClaimEventEnvelope.SCHEMA_VERSION,
        now.toString(), claim.getId(), version, ClaimEventSnapshot.of(claim, officerName));
    outbox.saveAndFlush(new OutboxEvent(eventId, claim.getId(), version, type.name(),
        json.writeValueAsString(envelope), now));
  }
}
