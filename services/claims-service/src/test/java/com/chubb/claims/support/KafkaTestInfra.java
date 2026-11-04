package com.chubb.claims.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;

/**
 * One real Kafka broker (the same image as docker-compose) for the whole test JVM. It is started once and
 * never stopped by a Spring context, so cached contexts keep a working producer; Ryuk removes it at exit.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestInfra {
  public static final String TOPIC = "claims.events.v1";
  public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.4.0");

  static {
    KAFKA.start();
    try (AdminClient admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      // Same shape as docker-compose kafka-init: 3 partitions, compacted.
      admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1).configs(Map.of("cleanup.policy", "compact"))))
          .all().get();
    } catch (Exception e) {
      throw new IllegalStateException("cannot create the test topic", e);
    }
  }

  @Bean
  DynamicPropertyRegistrar kafkaProperties() {
    return registry -> registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  /** Reads the whole topic from the beginning. Records come back per partition in offset order. */
  public static List<ConsumerRecord<String, String>> readAll(Duration quiet) {
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    List<ConsumerRecord<String, String>> out = new ArrayList<>();
    try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
      List<TopicPartition> parts = c.partitionsFor(TOPIC).stream()
          .map(i -> new TopicPartition(TOPIC, i.partition())).toList();
      c.assign(parts);
      c.seekToBeginning(parts);
      Map<TopicPartition, Long> end = c.endOffsets(parts);
      Set<TopicPartition> open = new java.util.HashSet<>(parts);
      long deadline = System.nanoTime() + quiet.toNanos();
      while (!open.isEmpty() && System.nanoTime() < deadline) {
        c.poll(Duration.ofMillis(200)).forEach(out::add);
        open.removeIf(tp -> c.position(tp) >= end.get(tp));
      }
    }
    return out;
  }

  /** Records for one claim, in the order the broker holds them. */
  public static List<ConsumerRecord<String, String>> forClaim(String claimId) {
    return readAll(Duration.ofSeconds(10)).stream().filter(r -> claimId.equals(r.key())).toList();
  }
}
