package com.chubb.claims.outbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class OutboxConfig {
  /**
   * Spring's default listener logs every failed send at ERROR with the whole payload, once per row per poll:
   * a log flood during an outage. The poller reports failures itself, once per failure run.
   */
  @Bean
  ProducerListener<Object, Object> quietProducerListener() {
    return new ProducerListener<>() { };
  }
}
