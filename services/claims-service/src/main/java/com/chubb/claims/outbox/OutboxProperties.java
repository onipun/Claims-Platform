package com.chubb.claims.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code app.outbox.*}. The poller is switched off in tests that have no Kafka. */
@ConfigurationProperties("app.outbox")
public record OutboxProperties(Poller poller) {
  public record Poller(Boolean enabled, Duration interval, Integer batchSize, Duration ackTimeout,
      Duration stuckWarnInterval) {
    public Poller(Boolean enabled, Duration interval, Integer batchSize, Duration ackTimeout) {
      this(enabled, interval, batchSize, ackTimeout, null);
    }
  }

  public OutboxProperties {
    Poller p = poller == null ? new Poller(null, null, null, null, null) : poller;
    poller = new Poller(p.enabled() == null || p.enabled(),
        p.interval() == null ? Duration.ofMillis(500) : p.interval(),
        p.batchSize() == null ? 100 : p.batchSize(),
        p.ackTimeout() == null ? Duration.ofSeconds(8) : p.ackTimeout(),
        p.stuckWarnInterval() == null ? Duration.ofSeconds(60) : p.stuckWarnInterval());
  }
}
