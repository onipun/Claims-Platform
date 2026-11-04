package com.chubb.claims.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes the outbox to {@code claims.events.v1} (D3, plan 6.3).
 *
 * <p>Per poll, in one transaction: take the single-publisher advisory lock (or return if another poller
 * holds it), read a batch in id order, send every row keyed by claim id, wait for
 * the broker's ack of each (trap 4), then mark only the acknowledged <em>prefix</em>. The first failure
 * stops the marking, so that row and everything after it is sent again next poll. A crash between ack and
 * mark therefore causes a duplicate and never a loss. Duplicates are absorbed by insights' rule
 * {@code event.version > stored.version} (D4); reordering cannot happen because only one poller publishes at
 * a time, which keeps the compacted topic's last record per claim the newest.
 *
 * <p>Nothing here is on a request path, and nothing here may throw out of {@link #poll()}: an exception
 * would be logged by the scheduler, but it must never change liveness (trap 15) or stop the schedule.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.poller.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {
  static final String TOPIC = "claims.events.v1";
  private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

  private final OutboxStore store;
  private final KafkaTemplate<String, String> kafka;
  private final TransactionTemplate tx;
  private final OutboxProperties.Poller props;

  private static final int LOCK_NOT_ACQUIRED = -2;
  private static final int FAILED = -1;

  private final Clock clock;

  /** True while publishing is failing. One WARN on entering the state, then one per stuck-warn interval. */
  private volatile boolean failing;
  private volatile Instant lastWarnAt;
  /** The oldest unpublished row seen in the latest batch: the one that blocks everything behind it. */
  private volatile OutboxStore.Row head;

  /** Consecutive polls that did not get the publisher lock, since when, and when that was last reported. */
  private int lockMisses;
  private Instant lockMissesSince;
  private Instant lastLockWarnAt;

  OutboxPoller(OutboxStore store, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
      OutboxProperties properties, Clock clock) {
    this.clock = clock;
    this.store = store;
    this.kafka = kafka;
    this.tx = tx;
    this.props = properties.poller();
  }

  @Scheduled(fixedDelayString = "${app.outbox.poller.interval:PT0.5S}", initialDelayString = "PT1S")
  public void poll() {
    head = null; // a row seen by an earlier poll says nothing about this one
    try {
      Integer published = tx.execute(status -> publishBatch());
      noteLock(published != null && published == LOCK_NOT_ACQUIRED);
      // Recovered only after a non-empty batch the broker acknowledged. A poll that found nothing to send,
      // or did not get the lock, proves nothing about the broker.
      if (failing && published != null && published > 0) {
        failing = false;
        log.info("Outbox publishing recovered");
      }
    } catch (Throwable t) { // never let anything end the schedule
      noteLock(false);
      if (t instanceof Error) {
        // not an expected publishing failure: say so at ERROR, with the trace, whatever the episode state
        failing = true;
        log.error("Outbox poll failed with an Error [{}]", where(clock.instant()), t);
      } else {
        fail("Outbox poll failed", t);
      }
    }
  }

  /**
   * Another instance holding the lock is normal for a poll or two, and a failure if it never lets go (a hung
   * session, a crashed holder that the database has not noticed): count the consecutive misses and report
   * once per stuck-warn interval. Any other result resets the count.
   */
  private void noteLock(boolean missed) {
    if (!missed) {
      lockMisses = 0;
      lockMissesSince = null;
      lastLockWarnAt = null;
      return;
    }
    Instant now = clock.instant();
    if (lockMisses++ == 0) {
      lockMissesSince = now;
      lastLockWarnAt = now;
      return;
    }
    if (Duration.between(lastLockWarnAt, now).compareTo(props.stuckWarnInterval()) >= 0) {
      lastLockWarnAt = now;
      log.warn("Outbox publisher lock not acquired for {} consecutive polls since {}; another instance holds it "
          + "and nothing is being published from here", lockMisses, lockMissesSince);
    }
  }

  /** Returns the number published, or -1 when the batch stopped at a failure. */
  private int publishBatch() {
    if (!store.tryAcquirePublisherLock()) return LOCK_NOT_ACQUIRED; // another poller is publishing; never interleave (D18)
    List<OutboxStore.Row> rows = store.nextBatch(props.batchSize());
    if (rows.isEmpty()) {
      head = null;
      return 0;
    }
    head = rows.get(0);

    List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>(rows.size());
    Throwable rejected = null;
    for (OutboxStore.Row row : rows) {
      try {
        futures.add(kafka.send(TOPIC, row.claimId().toString(), row.payload()));
      } catch (RuntimeException e) {
        // Stop sending: a later row for the same claim must not go out ahead of this one.
        rejected = e;
        break;
      }
    }

    long deadline = System.nanoTime() + props.ackTimeout().toNanos();
    List<Long> acked = new ArrayList<>(rows.size());
    Throwable failure = null; // from waiting; the rows sent before a rejected send are still awaited and marked
    for (int i = 0; i < futures.size() && failure == null; i++) {
      try {
        futures.get(i).get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        acked.add(rows.get(i).id());
      } catch (ExecutionException e) {
        failure = e.getCause() == null ? e : e.getCause();
      } catch (TimeoutException e) {
        failure = e;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        failure = e;
      }
    }
    // Rows already acked stay acked at the broker even if the mark below fails: that is the duplicate case.
    store.markPublished(acked);
    if (failure == null) failure = rejected;
    if (failure != null) {
      head = rows.get(acked.size()); // the first row that was not acknowledged
      fail("Outbox publish failed after " + acked.size() + " of " + rows.size() + " rows; will retry", failure);
      return FAILED;
    }
    head = null; // everything in the batch is acknowledged: nothing is blocking
    return acked.size();
  }

  private String where(Instant now) {
    OutboxStore.Row h = head;
    return h == null ? "head row unknown"
        : "head row id=" + h.id() + " claim=" + h.claimId() + " v" + h.version() + " age="
            + Duration.between(h.createdAt(), now).truncatedTo(ChronoUnit.SECONDS);
  }

  private static boolean expectedBrokerTrouble(Throwable t) {
    return t instanceof org.apache.kafka.common.KafkaException || t instanceof TimeoutException
        || t instanceof InterruptedException;
  }

  private void fail(String message, Throwable cause) {
    Instant now = clock.instant();
    String where = where(now);
    if (!failing) {
      failing = true;
      lastWarnAt = now;
      // the first WARN of an episode always carries the throwable
      log.warn("{}: {} [{}] (repeated every {} while publishing stays stuck)", message, cause.toString(), where,
          props.stuckWarnInterval(), cause);
    } else if (Duration.between(lastWarnAt, now).compareTo(props.stuckWarnInterval()) >= 0) {
      lastWarnAt = now;
      // a broker error is the expected cause and its one-line form is enough; anything else gets its trace
      if (expectedBrokerTrouble(cause)) {
        log.warn("Outbox publishing is still stuck: {} [{}]", cause.toString(), where);
      } else {
        log.warn("Outbox publishing is still stuck: {} [{}]", cause.toString(), where, cause);
      }
    } else if (log.isDebugEnabled()) {
      log.debug("{}", message, cause);
    }
  }
}
