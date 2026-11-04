package com.chubb.insights.projection;

import com.chubb.insights.event.ClaimEventParser;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Applies {@code claims.events.v1} to the projection. The value is a JSON string parsed here (no type headers),
 * so a poison message surfaces as an exception for the error handler instead of failing inside the Kafka
 * deserialiser. The offset is acknowledged only after the database statement has committed. Logs ids only.
 */
@Component
public class ClaimEventConsumer {
  private static final Logger log = LoggerFactory.getLogger(ClaimEventConsumer.class);

  private final ClaimEventParser parser;
  private final ProjectionStore store;

  ClaimEventConsumer(ClaimEventParser parser, ProjectionStore store) {
    this.parser = parser;
    this.store = store;
  }

  @KafkaListener(topics = "${app.projection.topic:claims.events.v1}",
      groupId = "${spring.kafka.consumer.group-id:insights-projection}")
  public void onRecord(ConsumerRecord<String, String> record, Acknowledgment ack) {
    var event = parser.parse(record.value());
    boolean applied = store.apply(event);
    if (log.isDebugEnabled()) {
      log.debug("claim {} v{} {} ({}-{}@{})", event.claimId(), event.claimVersion(),
          applied ? "applied" : "ignored (not newer)", record.topic(), record.partition(), record.offset());
    }
    ack.acknowledge();
  }
}
