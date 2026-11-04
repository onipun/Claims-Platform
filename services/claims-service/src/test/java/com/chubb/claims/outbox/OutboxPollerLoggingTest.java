package com.chubb.claims.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chubb.claims.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * I13 and I14, with no database and no broker: the store and the Kafka template are mocks, the log is a
 * captured appender, and the clock is moved by hand.
 */
class OutboxPollerLoggingTest {
  private final OutboxStore store = mock(OutboxStore.class);
  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
  private final TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
  private final Instant t0 = Instant.parse("2026-03-11T02:00:00Z");
  private MutableClock clock;
  private ListAppender<ILoggingEvent> logs;
  private OutboxPoller poller;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(t0);
    logs = new ListAppender<>();
    logs.start();
    Logger l = (Logger) LoggerFactory.getLogger(OutboxPoller.class);
    l.addAppender(logs);
    l.setLevel(Level.INFO);
    poller = new OutboxPoller(store, kafka, tx, new OutboxProperties(new OutboxProperties.Poller(
        true, Duration.ofMillis(500), 100, Duration.ofMillis(50), Duration.ofSeconds(60))), clock);
    when(store.tryAcquirePublisherLock()).thenReturn(true);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(OutboxPoller.class)).detachAppender(logs);
  }

  private static OutboxStore.Row row(long id, Instant created) {
    return new OutboxStore.Row(id, UUID.randomUUID(), 3, "ClaimUpdated", "{}", created);
  }

  private List<String> warns() {
    return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
  }

  private List<String> recovered() {
    return logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.contains("recovered")).toList();
  }

  private void brokerRejects() {
    when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("broker says no"));
  }

  @Test
  void aStuckHeadRowIsWarnedAboutAgainEveryIntervalWithItsIdAndAge() {
    when(store.nextBatch(anyInt())).thenReturn(List.of(row(42, t0.minusSeconds(120))));
    brokerRejects();

    poller.poll();
    assertThat(warns()).hasSize(1);
    assertThat(warns().get(0)).contains("id=42").contains("age=PT2M");

    for (int i = 0; i < 50; i++) { // 25 seconds of failing polls: nothing more
      clock.set(clock.instant().plus(Duration.ofMillis(500)));
      poller.poll();
    }
    assertThat(warns()).as("quiet inside the interval").hasSize(1);

    clock.set(clock.instant().plus(Duration.ofSeconds(40))); // 65 s after the first WARN
    poller.poll();
    assertThat(warns()).hasSize(2);
    assertThat(warns().get(1)).contains("still stuck").contains("id=42").contains("age=PT3M5S");

    clock.set(clock.instant().plus(Duration.ofSeconds(61)));
    poller.poll();
    assertThat(warns()).as("and again one interval later").hasSize(3);
    assertThat(warns().get(2)).contains("age=PT4M6S");
  }

  @Test
  void notGettingTheLockIsNeverRecoveryAndNeitherIsAnEmptyBatch() {
    when(store.nextBatch(anyInt())).thenReturn(List.of(row(7, t0)));
    brokerRejects();
    poller.poll(); // enters the failing state
    assertThat(warns()).hasSize(1);

    when(store.tryAcquirePublisherLock()).thenReturn(false);
    poller.poll();
    poller.poll();
    assertThat(recovered()).as("another poller holds the lock: nothing was acknowledged").isEmpty();

    when(store.tryAcquirePublisherLock()).thenReturn(true);
    when(store.nextBatch(anyInt())).thenReturn(List.of());
    poller.poll();
    assertThat(recovered()).as("an empty batch acknowledges nothing").isEmpty();

    // still failing: a later real failure past the interval must still be reported, proving the state was kept
    when(store.nextBatch(anyInt())).thenReturn(List.of(row(7, t0)));
    clock.set(clock.instant().plus(Duration.ofSeconds(61)));
    poller.poll();
    assertThat(warns()).hasSize(2);
  }

  @Test
  void recoveredIsLoggedOnceAfterAnAcknowledgedNonEmptyBatch() {
    when(store.nextBatch(anyInt())).thenReturn(List.of(row(7, t0)));
    brokerRejects();
    poller.poll();

    org.mockito.Mockito.doReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null))
        .when(kafka).send(anyString(), anyString(), anyString());
    poller.poll();
    assertThat(recovered()).hasSize(1);
    poller.poll();
    assertThat(recovered()).as("only once").hasSize(1);
  }

  private static CompletableFuture<SendResult<String, String>> ok() {
    return CompletableFuture.completedFuture(null);
  }

  private List<ILoggingEvent> warnEvents() {
    return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
  }

  @Test
  void aPartialBatchNamesTheFirstRowThatWasNotAcknowledgedAndTheFirstWarnCarriesTheThrowable() {
    when(store.nextBatch(anyInt())).thenReturn(List.of(row(1, t0), row(2, t0.minusSeconds(30)), row(3, t0)));
    org.mockito.Mockito.doReturn(ok(), CompletableFuture.<SendResult<String, String>>failedFuture(
            new org.apache.kafka.common.errors.TimeoutException("expired")), ok())
        .when(kafka).send(anyString(), anyString(), anyString());

    poller.poll();

    assertThat(warns()).hasSize(1);
    assertThat(warns().get(0)).contains("after 1 of 3 rows").contains("id=2").doesNotContain("id=1 ").doesNotContain("id=3");
    assertThat(warnEvents().get(0).getThrowableProxy()).as("the throwable is attached to the first WARN").isNotNull();
    org.mockito.Mockito.verify(store).markPublished(List.of(1L));
  }

  @Test
  void anAckTimeoutIsReportedWithTheRowItIsWaitingFor() {
    when(store.nextBatch(anyInt())).thenReturn(List.of(row(9, t0.minusSeconds(5))));
    org.mockito.Mockito.doReturn(new CompletableFuture<SendResult<String, String>>())
        .when(kafka).send(anyString(), anyString(), anyString());

    poller.poll(); // ackTimeout is 50 ms

    assertThat(warns()).hasSize(1);
    assertThat(warns().get(0)).contains("TimeoutException").contains("after 0 of 1 rows").contains("id=9").contains("age=PT5S");
    assertThat(warnEvents().get(0).getThrowableProxy()).isNotNull();
  }

  @Test
  void aNonKafkaFailureKeepsItsTraceOnTheStillStuckWarnToo() {
    when(store.nextBatch(anyInt())).thenReturn(List.of(row(42, t0)));
    brokerRejects(); // IllegalStateException: not a broker error
    poller.poll();
    clock.set(clock.instant().plus(Duration.ofSeconds(61)));
    poller.poll();
    assertThat(warns()).hasSize(2);
    assertThat(warns().get(1)).contains("still stuck");
    assertThat(warnEvents().get(1).getThrowableProxy().getClassName()).contains("IllegalStateException");
  }

  @Test
  void anErrorIsLoggedAtErrorWithItsTrace() {
    when(store.nextBatch(anyInt())).thenThrow(new AssertionError("boom"));
    poller.poll();
    List<ILoggingEvent> errors = logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0).getThrowableProxy().getClassName()).contains("AssertionError");
    assertThat(warns()).isEmpty();
  }

  @Test
  void theHeadRowIsForgottenWhenTheBatchIsEmptyOrAcknowledgedAndBeforeEachPoll() {
    when(store.nextBatch(anyInt())).thenReturn(List.of(row(5, t0)));
    brokerRejects();
    poller.poll();
    assertThat(warns().get(0)).contains("id=5");

    // the next poll fails before it reads any batch: the old head must not be named
    org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(store).nextBatch(anyInt());
    clock.set(clock.instant().plus(Duration.ofSeconds(61)));
    poller.poll();
    assertThat(warns()).hasSize(2);
    assertThat(warns().get(1)).contains("head row unknown").doesNotContain("id=5");

    // an acknowledged batch clears it for a later failure outside any batch
    org.mockito.Mockito.doReturn(List.of(row(6, t0))).when(store).nextBatch(anyInt());
    org.mockito.Mockito.doReturn(ok()).when(kafka).send(anyString(), anyString(), anyString());
    poller.poll();
    org.mockito.Mockito.doThrow(new IllegalStateException("db down again")).when(store).nextBatch(anyInt());
    clock.set(clock.instant().plus(Duration.ofSeconds(61)));
    poller.poll();
    assertThat(warns().get(warns().size() - 1)).contains("head row unknown");
  }

  @Test
  void consecutiveMissedLocksAreCountedWarnedAtTheIntervalAndResetByAnyOtherResult() {
    when(store.tryAcquirePublisherLock()).thenReturn(false);
    poller.poll();
    for (int i = 0; i < 40; i++) { // 20 seconds
      clock.set(clock.instant().plus(Duration.ofMillis(500)));
      poller.poll();
    }
    assertThat(warns()).as("quiet inside the interval").isEmpty();

    clock.set(clock.instant().plus(Duration.ofSeconds(41))); // 61 s after the first miss
    poller.poll();
    assertThat(warns()).hasSize(1);
    assertThat(warns().get(0)).contains("lock not acquired for 42 consecutive polls");

    clock.set(clock.instant().plus(Duration.ofSeconds(61)));
    poller.poll();
    assertThat(warns()).hasSize(2);
    assertThat(warns().get(1)).contains("43 consecutive polls");

    // any other result resets the count: a poll that gets the lock, then more misses, start from one again
    when(store.tryAcquirePublisherLock()).thenReturn(true);
    when(store.nextBatch(anyInt())).thenReturn(List.of());
    poller.poll();
    when(store.tryAcquirePublisherLock()).thenReturn(false);
    clock.set(clock.instant().plus(Duration.ofSeconds(120)));
    poller.poll(); // first miss of a new run: counted, not reported
    assertThat(warns()).hasSize(2);
    clock.set(clock.instant().plus(Duration.ofSeconds(61)));
    poller.poll();
    assertThat(warns()).hasSize(3);
    assertThat(warns().get(2)).contains("lock not acquired for 2 consecutive polls");
  }
}
