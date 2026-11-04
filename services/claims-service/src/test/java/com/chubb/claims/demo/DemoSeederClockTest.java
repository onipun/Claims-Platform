package com.chubb.claims.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chubb.claims.config.OffsetClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The seeder moves the shared clock into the past for one command and must always put it back. */
class DemoSeederClockTest {
  private final OffsetClock clock = new OffsetClock(Clock.systemUTC());
  private final DemoDataSeeder seeder = new DemoDataSeeder(null, null, null, null, null, null, null, clock);

  private boolean isNow() {
    return Duration.between(clock.instant(), Instant.now()).abs().compareTo(Duration.ofSeconds(5)) < 0;
  }

  @Test
  void theCommandSeesThePastAndTheClockIsBackAfterwards() {
    Instant past = Instant.now().minus(Duration.ofDays(10));
    Instant seen = seeder.shifted(past, clock::instant);
    assertThat(Duration.between(past, seen).abs()).isLessThan(Duration.ofSeconds(5));
    assertThat(isNow()).isTrue();
  }

  @Test
  void theOffsetResetsEvenWhenTheCommandThrows() {
    Instant past = Instant.now().minus(Duration.ofDays(10));
    assertThatThrownBy(() -> seeder.shifted(past, () -> {
      assertThat(isNow()).as("shifted inside the command").isFalse();
      throw new IllegalStateException("command failed");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(isNow()).as("reset after the exception").isTrue();
  }
}
