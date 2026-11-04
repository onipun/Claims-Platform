package com.chubb.claims.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One Postgres container and one controllable clock for every API test that imports this. */
@TestConfiguration(proxyBeanMethods = false)
public class TestInfra {
  /** Wednesday 2026-03-11 10:00 in Singapore. */
  public static final Instant DEFAULT_NOW = Instant.parse("2026-03-11T02:00:00Z");

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer("postgres:17-alpine");
  }

  @Bean
  StaffSide staffSide(com.chubb.claims.claim.ClaimRepository claims,
      org.springframework.transaction.support.TransactionTemplate tx, MutableClock clock) {
    return new StaffSide(claims, tx, clock);
  }

  @Bean
  @Primary
  MutableClock testClock() {
    return new MutableClock(DEFAULT_NOW);
  }
}
