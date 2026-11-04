package com.chubb.insights.config;

import com.chubb.insights.projection.FailureClassifier;
import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Error handling for the projection consumer (plan 6.3, 6.5):
 * <ul>
 *   <li>poison event (bad JSON, missing field, unsupported schema): no retry, straight to
 *       {@code claims.events.v1.DLT}, same partition;</li>
 *   <li>database outage: retried without limit, exponential backoff capped at {@code max-interval};
 *       the event is good and is never dead-lettered because Postgres was down;</li>
 *   <li>anything else unexpected: a bounded number of retries with the same backoff, then the DLT.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(KafkaConsumerConfig.Retry.class)
class KafkaConsumerConfig {

  @ConfigurationProperties("app.consumer.retry")
  record Retry(Duration initialInterval, Double multiplier, Duration maxInterval, Integer maxAttempts) {
    Retry {
      if (initialInterval == null) initialInterval = Duration.ofSeconds(1);
      if (multiplier == null) multiplier = 2.0;
      if (maxInterval == null) maxInterval = Duration.ofSeconds(30);
      if (maxAttempts == null) maxAttempts = 4;
    }
  }

  @Bean
  CommonErrorHandler projectionErrorHandler(KafkaTemplate<String, String> template, Retry retry) {
    DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(template,
        (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
    DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) -> {
      logDeadLetter(record, ex);
      dlt.accept(record, ex);
    }, bounded(retry));
    // Manual ack modes do not commit a dead-lettered record's offset unless asked; without this a restart
    // would redeliver (and re-dead-letter) the poison record.
    handler.setCommitRecovered(true);
    handler.addNotRetryableExceptions(com.chubb.insights.event.PoisonEventException.class);
    handler.setBackOffFunction((record, ex) -> FailureClassifier.isInfrastructureOutage(ex) && !FailureClassifier.isPoison(ex)
        ? unbounded(retry) : null); // null: use the handler's bounded default
    return handler;
  }

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KafkaConsumerConfig.class);
  private static final tools.jackson.databind.json.JsonMapper LENIENT = tools.jackson.databind.json.JsonMapper.builder().build();

  /**
   * The one signal that an event was set aside: a distinct, greppable ERROR line with the claim id (the record
   * key) and the claim version, and nothing from the payload or the exception message (both can quote it).
   * The version is read defensively: a malformed body just has none.
   */
  static void logDeadLetter(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record, Exception ex) {
    log.error("EVENT_DEAD_LETTERED claimId={} claimVersion={} topic={} partition={} offset={} reason={}",
        record.key(), claimVersion(record.value()), record.topic(), record.partition(), record.offset(),
        rootCauseName(ex));
  }

  /** Spring wraps the listener's exception (ListenerExecutionFailedException); the useful name is the innermost one. */
  private static String rootCauseName(Throwable ex) {
    Throwable t = ex;
    for (int i = 0; i < 10 && t.getCause() != null && t.getCause() != t; i++) t = t.getCause();
    return t.getClass().getSimpleName();
  }

  private static String claimVersion(Object value) {
    try {
      if (!(value instanceof String json)) return "unknown";
      tools.jackson.databind.JsonNode v = LENIENT.readTree(json).get("claimVersion");
      return v != null && v.isIntegralNumber() ? Long.toString(v.asLong()) : "unknown";
    } catch (RuntimeException e) {
      return "unknown";
    }
  }

  private static BackOff bounded(Retry r) {
    ExponentialBackOff b = new ExponentialBackOff(r.initialInterval().toMillis(), r.multiplier());
    b.setMaxInterval(r.maxInterval().toMillis());
    b.setMaxAttempts(r.maxAttempts());
    return b;
  }

  private static BackOff unbounded(Retry r) {
    ExponentialBackOff b = new ExponentialBackOff(r.initialInterval().toMillis(), r.multiplier());
    b.setMaxInterval(r.maxInterval().toMillis());
    return b; // no max elapsed time, no max attempts
  }
}
