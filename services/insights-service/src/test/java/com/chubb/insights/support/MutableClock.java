package com.chubb.insights.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

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
