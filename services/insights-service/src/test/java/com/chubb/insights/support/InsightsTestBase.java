package com.chubb.insights.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One Postgres and one Kafka (same images as docker-compose) for the whole test JVM, and one Spring context
 * shared by every test class that extends this. Topics mirror docker-compose: 3 partitions, plus the DLT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(InsightsTestBase.ClockConfig.class)
public abstract class InsightsTestBase {
  public static final String TOPIC = "claims.events.v1";
  public static final String DLT = "claims.events.v1.DLT";
  public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.4.0");
  public static final Instant T0 = Instant.parse("2026-06-15T12:00:00Z");
  public static final MutableClock CLOCK = new MutableClock(T0);

  static {
    POSTGRES.start();
    KAFKA.start();
    try (AdminClient admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1), new NewTopic(DLT, 3, (short) 1))).all().get();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfig {
    @Bean
    @Primary
    java.time.Clock testClock() {
      return CLOCK;
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("spring.datasource.hikari.connection-timeout", () -> "2000");
    r.add("spring.datasource.hikari.data-source-properties.socketTimeout", () -> "3");
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestTokens::jwksUri);
    r.add("app.consumer.retry.initial-interval", () -> "200ms");
    r.add("app.consumer.retry.max-interval", () -> "1s");
    r.add("app.consumer.retry.max-attempts", () -> "2");
    r.add("app.fx.usd-per-unit.SGD", () -> "0.74");
    r.add("app.fx.usd-per-unit.HKD", () -> "0.128");
    r.add("app.fx.usd-per-unit.IDR", () -> "0.000062");
  }

  @Autowired protected JdbcTemplate jdbc;

  @BeforeEach
  void resetState() {
    CLOCK.set(T0);
    jdbc.update("TRUNCATE claim_projection");
  }
}
