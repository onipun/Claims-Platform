package com.chubb.claims.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The seeder shifts the clock while the app may already be serving: the shift must stay on its own thread. */
class OffsetClockTest {

  @Test
  void aShiftOnOneThreadIsInvisibleToAnotherAndResetsOnlyThatThread() throws Exception {
    OffsetClock clock = new OffsetClock(Clock.systemUTC());
    Instant past = Instant.now().minus(Duration.ofDays(30));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch shifted = new CountDownLatch(1);
    CountDownLatch requestDone = new CountDownLatch(1);
    try {
      Future<Instant> seeder = pool.submit(() -> {
        clock.shiftTo(past);
        shifted.countDown();
        requestDone.await(10, TimeUnit.SECONDS); // stay shifted while the "request" thread reads
        Instant seen = clock.instant();
        clock.reset();
        return seen;
      });
      Future<Instant> request = pool.submit(() -> {
        shifted.await(10, TimeUnit.SECONDS);
        Instant seen = clock.instant();
        requestDone.countDown();
        return seen;
      });

      Instant requestSaw = request.get(10, TimeUnit.SECONDS);
      Instant seederSaw = seeder.get(10, TimeUnit.SECONDS);
      assertThat(Duration.between(requestSaw, Instant.now()).abs()).as("request thread sees real time")
          .isLessThan(Duration.ofSeconds(5));
      assertThat(Duration.between(seederSaw, past).abs()).as("seeder thread sees the shifted time")
          .isLessThan(Duration.ofSeconds(5));
    } finally {
      pool.shutdownNow();
    }
    assertThat(Duration.between(clock.instant(), Instant.now()).abs()).as("the test thread was never shifted")
        .isLessThan(Duration.ofSeconds(5));
  }
}
