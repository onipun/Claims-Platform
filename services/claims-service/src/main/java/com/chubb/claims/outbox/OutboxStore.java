package com.chubb.claims.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The poller's SQL steps. All need the poller's transaction, which holds the advisory lock. */
@Component
public class OutboxStore {
  public record Row(long id, UUID claimId, long version, String eventType, String payload, Instant createdAt) {}

  private final JdbcTemplate jdbc;
  private final Clock clock;

  OutboxStore(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** Fixed key of the single-publisher advisory lock ("OUTBOX" in ASCII). */
  static final long PUBLISHER_LOCK_KEY = 0x4F5554424F58L;

  /**
   * Takes the transaction-scoped publisher lock, or returns false when another poller holds it.
   *
   * <p>Why one publisher at a time: the topic is compacted (D18), which keeps the LAST record per claim id.
   * Two concurrent pollers can publish one claim's versions as [v1, v0]; the consumer's version rule
   * absorbs that live, but compaction would then keep v0 and a rebuild from the topic would restore a stale
   * claim. Order on the topic must therefore equal id order, which only a single publisher guarantees.
   * The lock is released by commit or rollback, so a crashed poller never leaves it held.
   */
  public boolean tryAcquirePublisherLock() {
    return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class,
        PUBLISHER_LOCK_KEY));
  }

  /**
   * Unpublished rows in id order. Call only after {@link #tryAcquirePublisherLock()} succeeded. Selecting by
   * {@code published_at IS NULL}, not by a high-water mark, is what survives ids that commit out of order
   * (trap 3): a late-committing lower id is simply picked up next poll.
   */
  public List<Row> nextBatch(int limit) {
    return jdbc.query("""
        SELECT id, claim_id, version, event_type, payload::text AS payload, created_at
        FROM outbox_event WHERE published_at IS NULL
        ORDER BY id LIMIT ?""",
        (rs, i) -> new Row(rs.getLong("id"), rs.getObject("claim_id", UUID.class), rs.getLong("version"),
            rs.getString("event_type"), rs.getString("payload"),
            rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()), limit);
  }

  public void markPublished(List<Long> idsInOrder) {
    if (idsInOrder.isEmpty()) return;
    jdbc.update("UPDATE outbox_event SET published_at = ? WHERE id = ANY (?)",
        java.sql.Timestamp.from(clock.instant()), idsInOrder.toArray(Long[]::new));
  }
}
