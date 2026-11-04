package com.chubb.claims;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * An upgrade, not a fresh install: migrate to V5, put data in the old shape (a user with a single limit,
 * a claim with ledger rows), then migrate to the latest and check what V6 and V7 did to that data.
 * Uses only db/migration: the V5 schema has no users, so the fixtures are inserted here.
 */
@Testcontainers
class MigrationUpgradeTest {
  @Container
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  private Flyway flyway(String target) {
    var cfg = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration");
    return (target == null ? cfg : cfg.target(target)).load();
  }

  @Test
  void v6CarriesTheOldLimitToEveryMarketAndV7BackfillsTheLedgerCurrency() {
    flyway("5").migrate();
    DataSource ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(ds);

    UUID officer = UUID.randomUUID();
    UUID manager = UUID.randomUUID();
    UUID holder = UUID.randomUUID();
    String hash = "$2a$10$levwaZQSjOZzm/nKjZ2T6e0g7hJaiyrXtnfKyrzDlDt/V/bnqRAk.";
    jdbc.update("insert into app_user (id, email, password_hash, name, role, authority_limit) values (?, 'o@x.test', ?, 'O', 'OFFICER', 12345.50)", officer, hash);
    jdbc.update("insert into app_user (id, email, password_hash, name, role, authority_limit) values (?, 'm@x.test', ?, 'M', 'MANAGER', NULL)", manager, hash);
    jdbc.update("insert into app_user (id, email, password_hash, name, role) values (?, 'h@x.test', ?, 'H', 'CLAIMANT')", holder, hash);
    jdbc.update("insert into user_market (user_id, market_code) values (?, 'SG'), (?, 'ID'), (?, 'SG'), (?, 'SG')", officer, officer, manager, holder);
    UUID policy = UUID.randomUUID();
    jdbc.update("insert into policy (id, policy_number, holder_id, market_code, line, coverage_limit, deductible, effective_from, effective_to) "
        + "values (?, 'P-1', ?, 'SG', 'MOTOR', 100000, 500, '2025-01-01', '2030-01-01')", policy, holder);
    UUID claim = UUID.randomUUID();
    jdbc.update("""
        insert into claim (id, claim_number, policy_id, claimant_id, market_code, line, claim_type, incident_at_local,
          market_zone, reported_at, loss_details, estimate, status, reserve, reserve_status, coverage_limit, deductible,
          currency, pickup_due_at, decision_due_at, updated_at, version)
        values (?, 'CLM-SG-2026-000001', ?, ?, 'SG', 'MOTOR', 'OWN_DAMAGE', now(), 'Asia/Singapore', now(), '{}'::jsonb,
          100, 'SUBMITTED', 50, 'PROVISIONAL', 100000, 500, 'SGD', now(), now(), now(), 0)""", claim, policy, holder);
    jdbc.update("insert into financial_entry (claim_id, entry_type, amount, actor_id, created_at) values (?, 'RESERVE_SET', 50, ?, now())", claim, holder);
    jdbc.update("insert into financial_entry (claim_id, entry_type, amount, actor_id, created_at) values (?, 'PAYMENT', 10, ?, now())", claim, holder);

    flyway(null).migrate();

    assertThat(jdbc.queryForList("select market_code || '=' || coalesce(authority_limit::text, 'null') from user_market "
        + "where user_id = ? order by 1", String.class, officer))
        .as("the officer's single limit is carried to each of their markets")
        .containsExactly("ID=12345.50", "SG=12345.50");
    assertThat(jdbc.queryForObject("select authority_limit from user_market where user_id = ?", BigDecimal.class, manager))
        .as("null stays null").isNull();
    assertThat(jdbc.queryForObject("select count(*) from information_schema.columns "
        + "where table_name = 'app_user' and column_name = 'authority_limit'", Integer.class)).isZero();

    assertThat(jdbc.queryForList("select currency from financial_entry where claim_id = ?", String.class, claim))
        .as("ledger currency backfilled from the claim, through the append-only trigger").containsExactly("SGD", "SGD");
    assertThat(jdbc.queryForObject("select count(*) from pg_trigger where tgname = 'financial_entry_append_only' and tgenabled = 'O'",
        Integer.class)).as("the trigger is switched back on").isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from financial_entry where claim_id = ?", Integer.class, claim)).isEqualTo(2);
  }
}
