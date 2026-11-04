package com.chubb.claims.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chubb.claims.support.KafkaTestInfra;
import com.chubb.claims.support.TestInfra;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The scheduled poller is OFF here; each test drives {@link OutboxPoller#poll()} by hand so the
 * interleavings are exact. Real Postgres and a real Kafka broker. "Broker down" is the broker container
 * paused with docker (frozen process, connections hang, like a network partition), not Toxiproxy, whose
 * image is not available offline here; the effect on the producer is the same (requests time out).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "app.outbox.poller.enabled=false")
@Import({TestInfra.class, KafkaTestInfra.class})
class OutboxDeliveryTest extends OutboxTestSupport {
  @Autowired @MockitoSpyBean OutboxStore store;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired TransactionTemplate tx;
  @Autowired OutboxProperties props;

  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void captureLogs() {
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(OutboxPoller.class)).addAppender(logs);
    ((Logger) LoggerFactory.getLogger(OutboxPoller.class)).setLevel(Level.INFO);
    drain(newPoller(100)); // start from an empty outbox
  }

  @AfterEach
  void unpauseBroker() {
    try {
      KafkaTestInfra.KAFKA.getDockerClient().unpauseContainerCmd(KafkaTestInfra.KAFKA.getContainerId()).exec();
    } catch (RuntimeException notPaused) {
      // already running
    }
    ((Logger) LoggerFactory.getLogger(OutboxPoller.class)).detachAppender(logs);
  }

  private OutboxPoller newPoller(int batchSize) {
    OutboxProperties.Poller p = props.poller();
    return new OutboxPoller(store, kafka, tx, new OutboxProperties(
        new OutboxProperties.Poller(true, p.interval(), batchSize, p.ackTimeout())),
        java.time.Clock.systemUTC());
  }

  private void drain(OutboxPoller poller) {
    for (int i = 0; i < 50 && unpublished() > 0; i++) poller.poll();
  }

  private long unpublished() {
    return jdbc.queryForObject("select count(*) from outbox_event where published_at is null", Long.class);
  }

  private long unpublished(UUID claim) {
    return jdbc.queryForObject("select count(*) from outbox_event where claim_id = ? and published_at is null",
        Long.class, claim);
  }

  private List<Long> versionsOnTopic(UUID claim) {
    return KafkaTestInfra.forClaim(claim.toString()).stream()
        .map(r -> parse(r.value()).get("claimVersion").asLong()).toList();
  }

  private long warns() {
    return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
  }

  @Test
  void brokerDownCommandsStillSucceedRowsWaitAndEverythingArrivesInOrderWhenItReturns() {
    var docker = KafkaTestInfra.KAFKA.getDockerClient();
    String broker = KafkaTestInfra.KAFKA.getContainerId();
    OutboxPoller poller = newPoller(100);
    newClaim("100.00");
    drain(poller); // the producer has metadata, as in a long-running service

    docker.pauseContainerCmd(broker).exec();
    List<UUID> claims = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      UUID id = newClaim("2000.00"); // 201 asserted inside newClaim
      ok("officer.sg", id, "pick-up", null);
      ok("officer.sg", id, "notes", Map.of("text", "n"));
      ok("officer.sg", id, "assessment", Map.of("reserve", "1900"));
      claims.add(id);
    }
    assertThat(unpublished()).as("12 commands, 12 rows waiting").isEqualTo(12);

    long start = System.nanoTime();
    poller.poll();
    poller.poll();
    poller.poll(); // none of these may throw
    long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
    assertThat(unpublished()).as("nothing marked while the broker is unreachable").isEqualTo(12);
    assertThat(warns()).as("one WARN for the whole failure run, not one per row or per poll").isEqualTo(1);
    assertThat(ms).as("each poll gives up within its own timeout").isLessThan(3 * 12_000);

    // trap 15: the outage must not touch health, overall or readiness
    Resp health = get("/actuator/health", null);
    assertThat(health.status()).as(health.body()).isEqualTo(200);
    assertThat(health.json().get("status").asString()).isEqualTo("UP");
    assertThat(get("/actuator/health/readiness", null).json().get("status").asString()).isEqualTo("UP");

    // commands keep working during the outage
    UUID during = newClaim("50.00");
    assertThat(outboxCount(during)).isEqualTo(1);

    docker.unpauseContainerCmd(broker).exec();
    await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).until(() -> {
      poller.poll();
      return unpublished() == 0;
    });

    for (UUID id : claims) {
      // A send that "failed" at the client (timeout) may still have been processed by the broker after it
      // unfroze, and the poller then sent the same rows again: so duplicates are expected, and a whole
      // replayed run can follow the originals (3 then 0). D4 absorbs both. What must hold: nothing missing,
      // versions first appear in increasing order, and the LAST record is the newest version (which is
      // what the compacted topic keeps).
      List<Long> seen = versionsOnTopic(id);
      assertThat(seen.stream().distinct().toList()).as("first appearances, per-claim order").containsExactly(0L, 1L, 2L, 3L);
      assertThat(seen.get(seen.size() - 1)).as("newest version last").isEqualTo(3L);
      System.out.println("BROKER-DOWN RESULT " + id + " versions on topic in partition order: " + seen);
    }
    assertThat(versionsOnTopic(during)).containsExactly(0L);
    assertThat(logs.list.stream().filter(e -> e.getFormattedMessage().contains("recovered")).count()).isEqualTo(1);
  }

  @Test
  void crashBetweenSendAndMarkRepublishesAndLosesNothing() {
    OutboxPoller poller = newPoller(100);
    UUID id = newClaim("3000.00");
    ok("officer.sg", id, "pick-up", null);
    ok("officer.sg", id, "notes", Map.of("text", "n"));

    doThrow(new IllegalStateException("simulated crash before mark")).doCallRealMethod()
        .when(store).markPublished(anyList());

    poller.poll(); // sends 3, then the mark throws: the transaction rolls back
    assertThat(unpublished(id)).as("rows still unpublished after the crash").isEqualTo(3);
    assertThat(versionsOnTopic(id)).as("but the broker has them").containsExactly(0L, 1L, 2L);

    poller.poll(); // next poll sends them again and marks
    assertThat(unpublished(id)).isZero();
    List<Long> onTopic = versionsOnTopic(id);
    assertThat(onTopic).as("duplicates, nothing lost, still in order").containsExactly(0L, 1L, 2L, 0L, 1L, 2L);
    assertThat(onTopic.get(onTopic.size() - 1)).as("last record (what compaction keeps) is the newest").isEqualTo(2L);
    assertThat(warns()).isEqualTo(1);
  }

  /**
   * Two pollers at once (as two replicas would run). The advisory lock lets one publish while the other
   * returns at once, so nothing is sent twice, nothing fails, and every claim's versions stay in order.
   */
  @Test
  void twoConcurrentPollersPublishEveryRowInOrder() throws Exception {
    List<UUID> claims = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      UUID id = newClaim("1000.00");
      ok("officer.sg", id, "pick-up", null);
      ok("officer.sg", id, "notes", Map.of("text", "a"));
      ok("officer.sg", id, "notes", Map.of("text", "b"));
      ok("officer.sg", id, "notes", Map.of("text", "c"));
      claims.add(id);
    }
    long rows = unpublished();
    assertThat(rows).isGreaterThanOrEqualTo(40);

    OutboxPoller a = newPoller(5); // small batches force the two pollers to interleave
    OutboxPoller b = newPoller(5);
    // Bounded by time, never by a count of polls. A poll that misses the lock returns in about 0.15 ms while a
    // batch holds it for about 6 ms, so a count measures how quickly a poller lost the race, not progress: with
    // 60 polls each, both pollers could run out with rows left (14 of 100 repeats) and no fault anywhere. Without
    // a count a run needs up to 419 polls, and all 40 rows take at most 511 ms even with 48 CPU spinners running;
    // 60 s only has to end a genuine stall.
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<?>> done = new ArrayList<>();
    for (OutboxPoller p : List.of(a, b)) {
      done.add(pool.submit(() -> {
        go.await();
        while (unpublished() > 0 && System.nanoTime() < deadline) p.poll();
        return null;
      }));
    }
    go.countDown();
    for (Future<?> f : done) f.get(120, java.util.concurrent.TimeUnit.SECONDS);
    pool.shutdown();

    assertThat(unpublished()).as("every row published before the deadline; poller log: %s", logs.list).isZero();
    assertThat(warns()).as("no failure, no lost lock").isZero();

    int regressions = 0;
    int duplicates = 0;
    Map<String, Long> storedByD4Rule = new HashMap<>();
    for (UUID id : claims) {
      long highest = -1;
      List<Long> seen = new ArrayList<>();
      for (ConsumerRecord<String, String> r : KafkaTestInfra.forClaim(id.toString())) {
        long v = parse(r.value()).get("claimVersion").asLong();
        if (seen.contains(v)) duplicates++;
        if (v < highest) regressions++;
        highest = Math.max(highest, v);
        seen.add(v);
        storedByD4Rule.merge(id.toString(), v, (old, nu) -> nu > old ? nu : old); // upsert only if newer
      }
      assertThat(seen).as("every version of " + id + " delivered").contains(0L, 1L, 2L, 3L, 4L);
    }
    System.out.println("TWO-POLLER RESULT: claims=" + claims.size() + " rows=" + rows
        + " duplicates=" + duplicates + " per-claim version regressions in partition order=" + regressions);
    storedByD4Rule.forEach((claim, v) -> assertThat(v).as("read model after D4 rule").isEqualTo(4L));
    assertThat(regressions).as("single publisher: versions are never out of order").isZero();
    assertThat(duplicates).as("single publisher: no row is sent twice by two live pollers").isZero();
  }

  /**
   * The finding, staged exactly. Poller A takes the publisher lock and reads v0 of a claim, then stalls; a
   * second poller runs meanwhile. Before the advisory lock it skipped A's row, sent v1 of the SAME claim and
   * committed first, leaving [1, 0] on a COMPACTED topic: compaction keeps the last record, so a rebuild
   * would restore stale v0. Now the second poller must publish nothing while A holds the lock, and the topic
   * ends in order with the newest last.
   */
  @Test
  void aSecondPollerPublishesNothingWhileTheFirstHoldsTheLockSoTheTopicStaysInOrder() throws Exception {
    UUID id = newClaim("700.00");
    ok("officer.sg", id, "pick-up", null);
    assertThat(unpublished()).isEqualTo(2);

    CountDownLatch aHoldsLock = new CountDownLatch(1);
    CountDownLatch bDone = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(1);
    Future<?> slowA = pool.submit(() -> tx.executeWithoutResult(s -> {
      assertThat(store.tryAcquirePublisherLock()).isTrue();
      List<OutboxStore.Row> rows = store.nextBatch(1);
      assertThat(rows).extracting(OutboxStore.Row::version).containsExactly(0L);
      aHoldsLock.countDown();
      try {
        bDone.await();
        kafka.send(OutboxPoller.TOPIC, id.toString(), rows.get(0).payload()).get(10, java.util.concurrent.TimeUnit.SECONDS);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
      store.markPublished(List.of(rows.get(0).id()));
    }));
    aHoldsLock.await();
    newPoller(1).poll(); // poller B: must not publish v1 ahead of A's v0
    assertThat(versionsOnTopic(id)).as("B published nothing while A holds the lock").isEmpty();
    assertThat(unpublished()).as("both rows still unpublished").isEqualTo(2);
    bDone.countDown();
    slowA.get(30, java.util.concurrent.TimeUnit.SECONDS);
    pool.shutdown();

    OutboxPoller later = newPoller(100);
    drain(later);
    List<Long> onTopic = versionsOnTopic(id);
    System.out.println("TWO-POLLER STAGED RESULT: versions in partition order = " + onTopic);
    assertThat(onTopic).as("in order, newest last").containsExactly(0L, 1L);
  }

  /** Trap 3: ids are handed out at insert, but commit order can differ. We select by published_at, not by id. */
  @Test
  void aLowerOutboxIdThatCommitsLateIsNotLost() throws Exception {
    OutboxPoller poller = newPoller(100);
    UUID first = newClaim("10.00");
    UUID second = newClaim("20.00");
    drain(poller);

    CountDownLatch aInserted = new CountDownLatch(1);
    CountDownLatch bCommitted = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(1);
    AtomicInteger lowId = new AtomicInteger();
    // Transaction A takes the LOWER id and holds its commit until B has committed the HIGHER id.
    Future<?> a = pool.submit(() -> tx.executeWithoutResult(s -> {
      lowId.set(insertRow(first, 100));
      aInserted.countDown();
      try {
        bCommitted.await();
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
    }));
    aInserted.await();
    int highId = tx.execute(s -> insertRow(second, 100));
    bCommitted.countDown();
    assertThat(highId).as("B's id is higher and B committed first").isGreaterThan(lowId.get());
    // poll while A is still uncommitted or just committing: B's row goes out
    poller.poll();
    a.get(30, java.util.concurrent.TimeUnit.SECONDS);
    pool.shutdown();

    drain(poller);
    assertThat(unpublished(first)).as("the lower id, committed late, was still picked up").isZero();
    assertThat(unpublished(second)).isZero();
    assertThat(versionsOnTopic(first)).contains(0L, 100L);
    assertThat(versionsOnTopic(second)).contains(0L, 100L);
  }

  /** A synthetic outbox row (a version far above anything real) so the race can be staged exactly. */
  private int insertRow(UUID claim, long version) {
    return jdbc.queryForObject("""
        insert into outbox_event (event_id, claim_id, version, event_type, payload, created_at)
        values (?, ?, ?, 'ClaimUpdated', ?::jsonb, now()) returning id""", Integer.class,
        UUID.randomUUID(), claim, version,
        "{\"claimId\":\"" + claim + "\",\"claimVersion\":" + version + ",\"synthetic\":true}");
  }
}
