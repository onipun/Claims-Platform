package com.chubb.claims;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs with the demo profile active, so db/demo is loaded by application-demo.yml. This test cannot tell whether
 * the base application.yml also names db/demo (it would pass either way); DemoLocationsConfigTest reads both
 * files and checks that. The seeder creates its claims through the real services: every one has ledger,
 * activity and outbox rows.
 */
@SpringBootTest(properties = {"app.outbox.poller.enabled=false", "app.jwt.key-dir=${java.io.tmpdir}/claims-demo-keys-${random.uuid}"})
@ActiveProfiles("demo")
@Testcontainers
class DemoProfileSeederTest {
  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired JdbcTemplate jdbc;

  @Test
  void demoUsersAreLoadedFromTheDemoLocation() {
    assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class)).isEqualTo(12);
    assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '1000' and success", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void everySeededClaimHasLedgerActivityAndOutboxRowsOnConsecutiveVersions() {
    List<UUID> claims = jdbc.queryForList("select id from claim", UUID.class);
    assertThat(claims).as("the seeder created its claims").hasSizeGreaterThanOrEqualTo(20);
    for (UUID id : claims) {
      assertThat(jdbc.queryForObject("select count(*) from financial_entry where claim_id = ?", Integer.class, id))
          .as("ledger rows for " + id).isPositive();
      assertThat(jdbc.queryForObject("select count(*) from claim_activity where claim_id = ?", Integer.class, id))
          .as("activity rows for " + id).isPositive();
      Long version = jdbc.queryForObject("select version from claim where id = ?", Long.class, id);
      assertThat(jdbc.queryForList("select version from outbox_event where claim_id = ? order by version", Long.class, id))
          .as("outbox rows for " + id).isEqualTo(java.util.stream.LongStream.rangeClosed(0, version).boxed().toList());
    }
    assertThat(jdbc.queryForObject("select count(*) from claim c where c.reserve <> coalesce((select amount from financial_entry e "
        + "where e.claim_id = c.id and e.entry_type = 'RESERVE_SET' order by e.id desc limit 1), 0)", Integer.class))
        .as("reserve equals the ledger for every seeded claim").isZero();
  }

  @Autowired com.chubb.claims.config.OffsetClock clock;

  @Test
  void seededClaimsAreBackDatedAndTheClockIsBackToNowAfterSeeding() {
    // reported_at comes from the shifted clock, not from the wall clock at seeding time
    assertThat(jdbc.queryForObject("select min(reported_at) from claim", java.time.OffsetDateTime.class))
        .isBefore(java.time.OffsetDateTime.now().minusDays(20));
    assertThat(jdbc.queryForObject("select count(*) from claim where reported_at < now() - interval '1 hour'", Integer.class))
        .as("most seeded claims are older than an hour").isGreaterThanOrEqualTo(20);
    assertThat(jdbc.queryForObject("select count(*) from claim where reported_at > now()", Integer.class)).isZero();
    assertThat(java.time.Duration.between(clock.instant(), java.time.Instant.now()).abs())
        .as("the shifted clock was reset on the seeding thread").isLessThan(java.time.Duration.ofSeconds(5));
  }
}
