package com.chubb.claims.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * The application clock. The offset is zero in every normal run; the only caller of
 * {@link #shiftTo} is the demo-profile seeder, and the shift applies to the calling thread only ({@code DemoDataSeeder}), which uses it to run real
 * commands "in the past" so ageing and overdue claims can be demonstrated. Tests replace the whole bean
 * with a MutableClock.
 */
public final class OffsetClock extends Clock {
  private final Clock base;
  /**
   * Per thread, so the seeder's shift never reaches a request served on another thread while the
   * application is already up. Only the thread that called {@link #shiftTo} sees the offset.
   */
  private final ThreadLocal<Duration> offset = ThreadLocal.withInitial(() -> Duration.ZERO);

  public OffsetClock(Clock base) {
    this.base = base;
  }

  /** Makes {@code instant()} report {@code when} (and then tick on) until {@link #reset()}. */
  public void shiftTo(Instant when) {
    // Whole microseconds, like the tick clock and like Postgres, so a stored value equals the one returned.
    offset.set(Duration.between(base.instant(), when.truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
  }

  public void reset() {
    offset.remove();
  }

  @Override public ZoneId getZone() { return base.getZone(); }
  @Override public Clock withZone(ZoneId zone) { return new OffsetClock(base.withZone(zone)); }
  @Override public Instant instant() { return base.instant().plus(offset.get()); }
}
