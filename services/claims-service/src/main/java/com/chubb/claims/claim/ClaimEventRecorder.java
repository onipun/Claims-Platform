package com.chubb.claims.claim;

/**
 * Called once from every command, after the claim row is flushed (so {@code getVersion()} is the
 * new version, trap 1). Task 8 supplies the outbox implementation; callers do not change.
 */
public interface ClaimEventRecorder {
  void record(Claim claim, String eventType);
}
