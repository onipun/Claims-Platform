package com.chubb.claims.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A settable clock for business time. Tokens use wall-clock time and are unaffected. */
public final class MutableClock extends Clock {
  private final AtomicReference<Instant> now;

  public MutableClock(Instant start) {
    this.now = new AtomicReference<>(start);
  }

  public void set(Instant instant) { now.set(instant); }

  @Override public ZoneId getZone() { return ZoneOffset.UTC; }
  @Override public Clock withZone(ZoneId zone) { return this; }
  @Override public Instant instant() { return now.get(); }
}
