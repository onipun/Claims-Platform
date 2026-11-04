package com.chubb.claims;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chubb.claims.claim.Claim;
import com.chubb.claims.claim.domain.Actor;
import com.chubb.claims.claim.domain.ClaimStatus;
import com.chubb.claims.claim.domain.ClaimType;
import com.chubb.claims.claim.domain.Command;
import com.chubb.claims.claim.domain.Line;
import com.chubb.claims.claim.domain.MotorLoss;
import com.chubb.claims.claim.domain.Role;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Flyway runs on an empty database at context start, and Hibernate's ddl-auto=validate then
 * checks every mapped entity against the migrated schema. A failure of either fails the context.
 */
@SpringBootTest
@Testcontainers
public class MigrationTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired JdbcTemplate jdbc;
  @Autowired EntityManager em;

  private UUID userId(String email) {
    return jdbc.queryForObject("select id from app_user where email = ?", UUID.class, email);
  }

  /** Inserts a claim by SQL; tests are @Transactional, so it is rolled back. */
  private UUID sqlClaimWithLedgerAndActivity() {
    UUID claimant = userId("claimant.sg@demo.test");
    UUID policy = jdbc.queryForObject(
        "select id from policy where policy_number = 'MOT-SG-100001'", UUID.class);
    UUID claim = UUID.randomUUID();
    jdbc.update("""
        insert into claim (id, claim_number, policy_id, claimant_id, market_code, line, claim_type,
          incident_at_local, market_zone, reported_at, loss_details, estimate, status, reserve,
          reserve_status, coverage_limit, deductible, currency, pickup_due_at, decision_due_at,
          updated_at, version)
        values (?, 'CLM-SG-2026-999999', ?, ?, 'SG', 'MOTOR', 'OWN_DAMAGE', now(), 'Asia/Singapore',
          now(), '{}'::jsonb, 100, 'SUBMITTED', 0, 'PROVISIONAL', 100000, 500, 'SGD', now(), now(),
          now(), 0)""", claim, policy, claimant);
    jdbc.update("insert into financial_entry (claim_id, entry_type, amount, currency, actor_id, created_at) "
        + "values (?, 'RESERVE_SET', 0, 'SGD', ?, now())", claim, claimant);
    jdbc.update("insert into claim_activity (claim_id, activity_type, actor_id, visibility, created_at) "
        + "values (?, 'SUBMITTED', ?, 'CLAIMANT', now())", claim, claimant);
    return claim;
  }

  @Test
  void flywayAppliedAndSeedHasTheExpectedReferenceData() {
    assertThat(jdbc.queryForList("select code || '/' || currency || '/' || zone_id from market order by code", String.class))
        .containsExactly("AU/AUD/Australia/Sydney", "HK/HKD/Asia/Hong_Kong", "ID/IDR/Asia/Jakarta",
            "MY/MYR/Asia/Kuala_Lumpur", "SG/SGD/Asia/Singapore", "TH/THB/Asia/Bangkok");
    assertThat(jdbc.queryForMap("select market_code, line, rule_type, source_url from market_rule"))
        .containsEntry("market_code", "SG").containsEntry("line", "MOTOR")
        .containsEntry("rule_type", "NEXT_WORKING_DAY")
        .containsEntry("source_url",
            "https://gia.org.sg/motor-insurance/22-premium-renewal-of-policy/348-motor-claims-framewor");
    assertThat(jdbc.queryForObject("select count(*) from claim", Integer.class)).isZero();
  }

  @Test
  void usersAndAuthorityLimitsPerMarket() {
    Map<String, Object> limits = new java.util.HashMap<>();
    jdbc.query("select u.email, um.market_code, um.authority_limit from user_market um join app_user u on u.id = um.user_id",
        rs -> { limits.put(rs.getString(1) + "/" + rs.getString(2), rs.getBigDecimal(3) == null ? "null" : rs.getBigDecimal(3)); });
    assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class))
        .as("db/demo V1000 (12 users), loaded because the test properties opt in").isEqualTo(12);
    assertThat(limits.get("officer.my@demo.test/MY")).isEqualTo(new BigDecimal("20000.00"));
    assertThat(limits.get("officer.id@demo.test/ID")).isEqualTo(new BigDecimal("200000000.00"));
    assertThat(limits.get("officer.sg@demo.test/SG")).isEqualTo(new BigDecimal("10000.00"));
    assertThat(limits.get("officer2.sg@demo.test/SG")).isEqualTo(new BigDecimal("50000.00"));
    assertThat(limits.get("officer.hk@demo.test/HK")).isEqualTo(new BigDecimal("100000.00"));
    assertThat(limits.get("manager.sg@demo.test/SG")).isEqualTo("null");
    assertThat(limits.get("manager.apac@demo.test/ID")).isEqualTo("null");
    assertThat(jdbc.queryForList(
            "select market_code from user_market where user_id = ? order by 1", String.class,
            userId("manager.apac@demo.test")))
        .containsExactly("AU", "HK", "ID", "MY", "SG", "TH");
    assertThat(jdbc.queryForList(
            "select market_code from user_market where user_id = ?", String.class, userId("officer.hk@demo.test")))
        .containsExactly("HK");
    assertThat(jdbc.queryForObject(
            "select count(*) from information_schema.columns where table_name = 'app_user' and column_name = 'authority_limit'",
            Integer.class)).as("the old column is gone").isZero();
  }

  @Test
  void seededBcryptHashesMatchPassword1() {
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    List<String> hashes = jdbc.queryForList("select password_hash from app_user", String.class);
    assertThat(hashes).hasSize(12).allSatisfy(h -> {
      assertThat(h).startsWith("$2a$10$");
      assertThat(encoder.matches("Password1!", h)).isTrue();
      assertThat(encoder.matches("password1!", h)).isFalse();
    });
  }

  @Test
  void policiesCoverEachClaimantAndTheConflictFixture() {
    for (String email : List.of("claimant.sg@demo.test", "claimant2.sg@demo.test", "claimant.hk@demo.test",
        "claimant.my@demo.test", "claimant.id@demo.test")) {
      assertThat(jdbc.queryForList("select line from policy where holder_id = ? order by line", String.class, userId(email)))
          .containsExactly("MOTOR", "PROPERTY");
    }
    assertThat(jdbc.queryForObject("select count(*) from policy where holder_id = ?", Integer.class, userId("officer2.sg@demo.test")))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
            "select count(*) from policy p join app_user u on u.id = p.holder_id "
                + "join user_market um on um.user_id = u.id and um.market_code = p.market_code", Integer.class))
        .isEqualTo(11);
    assertThat(jdbc.queryForObject("select min(effective_to) from policy", java.sql.Date.class).toLocalDate().getYear())
        .isEqualTo(2030);
  }

  @Test
  void documentRequirementsAreSourcedForMalaysiaAndPlaceholderElsewhere() {
    assertThat(jdbc.queryForObject(
            "select count(*) from document_requirement where market_code = 'MY' and (placeholder or source is null)", Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject(
            "select count(*) from document_requirement where market_code <> 'MY' and (not placeholder or source is not null)", Integer.class))
        .isZero();
  }

  /** R9: the exact document set per claim type, in every market (V3__reference_data.sql:25-56). A wrong count in one market fails. */
  public static final Map<String, List<String>> MOTOR_DOCUMENTS = Map.of(
      "OWN_DAMAGE", List.of("DRIVER_ID_AND_LICENCE", "INSURED_ID_AND_LICENCE", "POLICE_REPORT", "REPAIRER_ESTIMATE",
          "VEHICLE_REGISTRATION_CARD"),
      "THIRD_PARTY_DAMAGE", List.of("DRIVER_ID_AND_LICENCE", "INSURED_ID_AND_LICENCE", "POLICE_REPORT", "REPAIRER_ESTIMATE",
          "THIRD_PARTY_POLICE_REPORT", "THIRD_PARTY_VEHICLE_DECLARATION", "VEHICLE_REGISTRATION_CARD"),
      "TOTAL_LOSS", List.of("DRIVER_ID_AND_LICENCE", "INSURED_ID_AND_LICENCE", "POLICE_REPORT", "REPAIRER_ESTIMATE",
          "VEHICLE_REGISTRATION_CARD"),
      "WINDSCREEN", List.of("DRIVER_ID_AND_LICENCE", "INSURED_ID_AND_LICENCE", "PHOTOS_BEFORE_AFTER", "REPAIRER_BILL",
          "VEHICLE_REGISTRATION_CARD"),
      "THEFT", List.of("IDENTITY_DOCUMENT", "POLICE_REPORT", "VEHICLE_REGISTRATION_CARD"));

  @Test
  void documentRequirementsAreTheExactSetPerClaimTypeInEveryMarket() {
    List<String> markets = jdbc.queryForList("select code from market order by code", String.class);
    assertThat(markets).hasSize(6);
    for (String market : markets) {
      assertThat(jdbc.queryForObject("select count(*) from document_requirement where market_code = ?", Integer.class, market))
          .as("rows in %s", market).isEqualTo(25);
      assertThat(jdbc.queryForObject("select count(distinct line) from document_requirement where market_code = ?",
          Integer.class, market)).as("only MOTOR has a list").isEqualTo(1);
      for (var e : MOTOR_DOCUMENTS.entrySet()) {
        assertThat(jdbc.queryForList("select document_type from document_requirement where market_code = ? and line = 'MOTOR' "
            + "and claim_type = ? order by document_type", String.class, market, e.getKey()))
            .as("%s / %s", market, e.getKey()).containsExactlyElementsOf(e.getValue());
      }
    }
    assertThat(jdbc.queryForObject("select count(*) from document_requirement", Integer.class)).isEqualTo(25 * 6);
  }

  @Test
  void claimNumbersComeFromTheSequenceAndDoNotTruncateAfterAMillion() {
    assertThat(jdbc.queryForObject("select next_claim_number('SG', 2026)", String.class))
        .matches("CLM-SG-2026-\\d{6}");
    jdbc.queryForObject("select setval('claim_number_seq', 999998)", Long.class);
    assertThat(jdbc.queryForObject("select next_claim_number('SG', 2026)", String.class)).isEqualTo("CLM-SG-2026-999999");
    assertThat(jdbc.queryForObject("select next_claim_number('SG', 2026)", String.class)).isEqualTo("CLM-SG-2026-1000000");
    assertThat(jdbc.queryForObject("select next_claim_number('SG', 2026)", String.class)).isEqualTo("CLM-SG-2026-1000001");
    jdbc.queryForObject("select setval('claim_number_seq', 123455)", Long.class);
    assertThat(jdbc.queryForObject("select next_claim_number('HK', 2027)", String.class)).isEqualTo("CLM-HK-2027-123456");
  }

  @Test
  @Transactional
  void financialEntryRefusesUpdate() {
    UUID claim = sqlClaimWithLedgerAndActivity();
    assertThatThrownBy(() -> jdbc.update("update financial_entry set amount = 1 where claim_id = ?", claim))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
  }

  @Test
  @Transactional
  void financialEntryRefusesDelete() {
    UUID claim = sqlClaimWithLedgerAndActivity();
    assertThatThrownBy(() -> jdbc.update("delete from financial_entry where claim_id = ?", claim))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
  }

  @Test
  @Transactional
  void claimActivityRefusesUpdate() {
    UUID claim = sqlClaimWithLedgerAndActivity();
    assertThatThrownBy(() -> jdbc.update("update claim_activity set message = 'x' where claim_id = ?", claim))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
  }

  @Test
  @Transactional
  void claimActivityRefusesDelete() {
    UUID claim = sqlClaimWithLedgerAndActivity();
    assertThatThrownBy(() -> jdbc.update("delete from claim_activity where claim_id = ?", claim))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
  }

  @Test
  @Transactional
  void claimIdempotencyKeyIsUniquePerClaimant() {
    UUID claim = sqlClaimWithLedgerAndActivity();
    jdbc.update("update claim set idempotency_key = 'k1' where id = ?", claim);
    assertThatThrownBy(() -> jdbc.update(
            "insert into claim select gen_random_uuid(), 'CLM-SG-2026-999998', policy_id, claimant_id, market_code, line, "
                + "claim_type, reporting_channel, incident_at_local, market_zone, reported_at, late, late_reason, contact_phone, "
                + "description, loss_details, estimate, status, assigned_officer_id, reserve, reserve_status, paid, approved_amount, "
                + "rejection_code, rejection_explanation, coverage_limit, deductible, currency, pickup_due_at, decision_due_at, "
                + "first_picked_up_at, decided_at, closed_at, idempotency_key, updated_at, version from claim where id = ?", claim))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("claim_idempotency_uq");
  }

  @Test
  @Transactional
  void claimEntityRoundTripsAndEveryCommandBumpsTheVersion() {
    UUID claimant = userId("claimant.sg@demo.test");
    UUID policy = jdbc.queryForObject("select id from policy where policy_number = 'MOT-SG-100001'", UUID.class);
    Instant now = Instant.parse("2026-11-04T04:00:00Z");
    String number = jdbc.queryForObject("select next_claim_number('SG', 2026)", String.class);
    Claim claim = Claim.submit(new Claim.NewClaim(number, policy, claimant, "SG", Line.MOTOR, ClaimType.OWN_DAMAGE,
        LocalDateTime.parse("2026-11-03T09:00:00"), "Asia/Singapore", now, false, null, "+6591234567", "dent",
        new MotorLoss("SGX1234A", "Tan", null, false, null), new BigDecimal("1500.00"),
        new BigDecimal("100000.00"), new BigDecimal("500.00"), "SGD", now.plusSeconds(86400),
        now.plusSeconds(14 * 86400), "key-1", null), now);
    em.persist(claim);
    em.flush();
    assertThat(claim.getVersion()).isZero();
    assertThat(claim.getReserve()).isEqualByComparingTo("1000");

    Actor officer = new Actor(userId("officer.sg@demo.test"), Role.OFFICER, Set.of("SG"), new BigDecimal("10000"));
    claim.execute(Command.pickUp(officer), now.plusSeconds(60));
    em.flush();
    assertThat(claim.getVersion()).isEqualTo(1);
    // A timeline-only command still moves the version (trap 2).
    claim.execute(Command.note(officer, "called the claimant"), now.plusSeconds(60));
    em.flush();
    assertThat(claim.getVersion()).isEqualTo(2);
    assertThat(claim.getStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);

    em.clear();
    Claim loaded = em.find(Claim.class, claim.getId());
    assertThat(loaded.getVersion()).isEqualTo(2);
    assertThat(loaded.getLossDetails()).isEqualTo(new MotorLoss("SGX1234A", "Tan", null, false, null));
    assertThat(loaded.getLedger()).hasSize(1);
    assertThat(loaded.getActivities()).hasSize(3);
    assertThat(loaded.position().reserve()).isEqualByComparingTo("1000");
    assertThat(jdbc.queryForObject("select loss_details ->> 'line' from claim where id = ?", String.class, claim.getId()))
        .isEqualTo("MOTOR");
  }

  // ---- constraints added in V7: each must refuse bad data by direct SQL ----

  /** Each refused statement runs under a savepoint, so the surrounding test transaction stays usable. */
  private void assertRejected(String constraint, String sql, Object... args) {
    jdbc.execute("savepoint refused");
    try {
      assertThatThrownBy(() -> jdbc.update(sql, args)).as(constraint)
          .isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
    } finally {
      jdbc.execute("rollback to savepoint refused");
    }
  }

  @Test
  @Transactional
  void claimCheckConstraintsRejectBadData() {
    UUID c = sqlClaimWithLedgerAndActivity();
    assertRejected("claim_line_ck", "update claim set line = 'MARINE' where id = ?", c);
    assertRejected("claim_status_ck", "update claim set status = 'LOST' where id = ?", c);
    assertRejected("claim_reserve_status_ck", "update claim set reserve_status = 'GUESSED' where id = ?", c);
    assertRejected("claim_coverage_limit_positive", "update claim set coverage_limit = 0 where id = ?", c);
    assertRejected("claim_deductible_non_negative", "update claim set deductible = -1 where id = ?", c);
    assertRejected("claim_paid_within_approved", "update claim set approved_amount = 100, paid = 100.01 where id = ?", c);
    // nothing is paid before an amount is approved
    assertRejected("claim_paid_within_approved", "update claim set approved_amount = null, paid = 1 where id = ?", c);
    jdbc.update("update claim set approved_amount = 100, paid = 100 where id = ?", c); // equal is fine
    jdbc.update("update claim set approved_amount = null, paid = 0 where id = ?", c);
  }

  @Test
  @Transactional
  void idrAmountsMustBeWholeOnClaimAndLedger() {
    UUID c = sqlClaimWithLedgerAndActivity();
    UUID policy = jdbc.queryForObject("select id from policy where policy_number = 'MOT-ID-400001'", UUID.class);
    UUID idClaimant = userId("claimant.id@demo.test");
    String insert = """
        insert into claim (id, claim_number, policy_id, claimant_id, market_code, line, claim_type,
          incident_at_local, market_zone, reported_at, loss_details, estimate, status, reserve,
          reserve_status, coverage_limit, deductible, currency, pickup_due_at, decision_due_at,
          updated_at, version, paid, approved_amount)
        values (gen_random_uuid(), ?, ?, ?, 'ID', 'MOTOR', 'OWN_DAMAGE', now(), 'Asia/Jakarta', now(), '{}'::jsonb,
          ?, 'SUBMITTED', ?, 'PROVISIONAL', ?, ?, 'IDR', now(), now(), now(), 0, 0, ?)""";
    // whole amounts are accepted
    jdbc.update(insert, "CLM-ID-2026-900001", policy, idClaimant, new BigDecimal("1000"), new BigDecimal("0"),
        new BigDecimal("500000000"), new BigDecimal("2000000"), null);
    // a fraction in any money column is refused
    String name = "claim_idr_whole_amounts";
    assertRejected(name, insert, "CLM-ID-2026-900002", policy, idClaimant, new BigDecimal("1000.50"),
        new BigDecimal("0"), new BigDecimal("500000000"), new BigDecimal("2000000"), null);
    assertRejected(name, insert, "CLM-ID-2026-900003", policy, idClaimant, new BigDecimal("1000"),
        new BigDecimal("10.5"), new BigDecimal("500000000"), new BigDecimal("2000000"), null);
    assertRejected(name, insert, "CLM-ID-2026-900004", policy, idClaimant, new BigDecimal("1000"),
        new BigDecimal("0"), new BigDecimal("500000000.01"), new BigDecimal("2000000"), null);
    assertRejected(name, insert, "CLM-ID-2026-900005", policy, idClaimant, new BigDecimal("1000"),
        new BigDecimal("0"), new BigDecimal("500000000"), new BigDecimal("2000000.5"), null);
    assertRejected(name, insert, "CLM-ID-2026-900006", policy, idClaimant, new BigDecimal("1000"),
        new BigDecimal("0"), new BigDecimal("500000000"), new BigDecimal("2000000"), new BigDecimal("99.5"));
    // paid is a money column too (approved is whole here, so only paid's fraction can be the cause)
    String insertPaid = insert.replace("now(), 0, 0, ?)", "now(), 0, ?, ?)");
    assertThat(insertPaid).isNotEqualTo(insert);
    jdbc.update(insertPaid, "CLM-ID-2026-900007", policy, idClaimant, new BigDecimal("1000"), new BigDecimal("0"),
        new BigDecimal("500000000"), new BigDecimal("2000000"), new BigDecimal("50"), new BigDecimal("99"));
    assertRejected(name, insertPaid, "CLM-ID-2026-900008", policy, idClaimant, new BigDecimal("1000"),
        new BigDecimal("0"), new BigDecimal("500000000"), new BigDecimal("2000000"), new BigDecimal("50.5"),
        new BigDecimal("99"));
    // the ledger too
    UUID idc = jdbc.queryForObject("select id from claim where claim_number = 'CLM-ID-2026-900001'", UUID.class);
    jdbc.update("insert into financial_entry (claim_id, entry_type, amount, currency, actor_id, created_at) "
        + "values (?, 'RESERVE_SET', 5000, 'IDR', ?, now())", idc, idClaimant);
    assertRejected("financial_entry_idr_whole_amount",
        "insert into financial_entry (claim_id, entry_type, amount, currency, actor_id, created_at) "
            + "values (?, 'RESERVE_SET', 5000.25, 'IDR', ?, now())", idc, idClaimant);
    // a ledger row in another currency than its claim's is refused
    assertRejected("financial_entry_claim_currency_fk",
        "insert into financial_entry (claim_id, entry_type, amount, currency, actor_id, created_at) "
            + "values (?, 'RESERVE_SET', 5000, 'SGD', ?, now())", idc, idClaimant);
    // SGD keeps its two decimals
    jdbc.update("insert into financial_entry (claim_id, entry_type, amount, currency, actor_id, created_at) "
        + "values (?, 'RESERVE_SET', 5000.25, 'SGD', ?, now())", c, userId("claimant.sg@demo.test"));
  }

  @Test
  @Transactional
  void ledgerRowMustCarryTheCurrencyOfItsClaim() {
    UUID c = sqlClaimWithLedgerAndActivity(); // an SGD claim
    jdbc.update("insert into financial_entry (claim_id, entry_type, amount, currency, actor_id, created_at) "
        + "values (?, 'RESERVE_SET', 10, 'SGD', ?, now())", c, userId("claimant.sg@demo.test"));
    assertRejected("financial_entry_claim_currency_fk",
        "insert into financial_entry (claim_id, entry_type, amount, currency, actor_id, created_at) "
            + "values (?, 'RESERVE_SET', 10, 'USD', ?, now())", c, userId("claimant.sg@demo.test"));
  }

  @Test
  @Transactional
  void ledgerRowsMustCarryACurrency() {
    UUID c = sqlClaimWithLedgerAndActivity();
    assertThatThrownBy(() -> jdbc.update(
            "insert into financial_entry (claim_id, entry_type, amount, actor_id, created_at) values (?, 'PAYMENT', 1, ?, now())",
            c, userId("claimant.sg@demo.test")))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("currency");
  }

  @Test
  @Transactional
  void claimMustAgreeWithItsPolicyMarketAndLineAndWithTheMarketCurrency() {
    UUID c = sqlClaimWithLedgerAndActivity();
    // a copy with no ledger rows: a currency change on a claim that has them meets the ledger key first
    jdbc.update("insert into claim select gen_random_uuid(), 'CLM-SG-2026-999997', policy_id, claimant_id, market_code, line, "
        + "claim_type, reporting_channel, incident_at_local, market_zone, reported_at, late, late_reason, contact_phone, "
        + "description, loss_details, estimate, status, assigned_officer_id, reserve, reserve_status, paid, approved_amount, "
        + "rejection_code, rejection_explanation, coverage_limit, deductible, currency, pickup_due_at, decision_due_at, "
        + "first_picked_up_at, decided_at, closed_at, idempotency_key, updated_at, version from claim where id = ?", c);
    UUID bare = jdbc.queryForObject("select id from claim where claim_number = 'CLM-SG-2026-999997'", UUID.class);
    // policy MOT-SG-100001 is SG / MOTOR
    assertRejected("claim_policy_market_line_fk", "update claim set line = 'PROPERTY' where id = ?", bare);
    assertRejected("claim_policy_market_line_fk", "update claim set market_code = 'HK', currency = 'HKD' where id = ?", bare);
    // the market owns the currency
    assertRejected("claim_market_currency_fk", "update claim set currency = 'USD' where id = ?", bare);
    assertRejected("claim_market_currency_fk", "update claim set currency = 'HKD' where id = ?", bare);
    // and with ledger rows present the ledger key refuses a currency change as well
    assertRejected("financial_entry_claim_currency_fk", "update claim set market_code = 'HK', currency = 'HKD' where id = ?", c);
  }

  @Test
  @Transactional
  void policyConstraintsStillHold() {
    UUID holder = userId("claimant.sg@demo.test");
    assertRejected("policy_coverage_limit_check", "insert into policy (policy_number, holder_id, market_code, line, coverage_limit, "
        + "deductible, effective_from, effective_to) values ('X-1', ?, 'SG', 'MOTOR', 0, 0, '2025-01-01', '2026-01-01')", holder);
    assertThat(jdbc.queryForObject(
            "select count(*) from pg_constraint where conname = 'policy_id_market_line_uq'", Integer.class)).isEqualTo(1);
  }

  @Test
  void theNewIndexesExist() {
    assertThat(jdbc.queryForList("select indexname from pg_indexes where tablename in ('claim', 'claim_activity', 'info_request')",
        String.class))
        .contains("claim_policy_idx", "claim_unassigned_pickup_idx", "claim_market_updated_idx", "claim_officer_updated_idx",
            "info_request_claim_asked_idx", "claim_activity_claim_idx")
        .doesNotContain("claim_activity_timeline_idx", "claim_officer_idx");
    assertThat(jdbc.queryForObject("select indexdef from pg_indexes where indexname = 'claim_unassigned_pickup_idx'", String.class))
        .contains("(market_code, pickup_due_at, claim_number)").contains("WHERE (assigned_officer_id IS NULL)");
    assertThat(jdbc.queryForObject("select indexdef from pg_indexes where indexname = 'claim_market_updated_idx'", String.class))
        .contains("(market_code, updated_at DESC, claim_number)");
    assertThat(jdbc.queryForObject("select indexdef from pg_indexes where indexname = 'claim_officer_updated_idx'", String.class))
        .contains("(assigned_officer_id, updated_at DESC, claim_number)");
  }

  /** The claim's claimant is its policy's holder; a claim on someone else's policy is refused by the database. */
  @Test
  @Transactional
  void aClaimsClaimantMustBeItsPoliciesHolder() {
    UUID c = sqlClaimWithLedgerAndActivity();
    assertRejected("claim_policy_holder_fk", "update claim set claimant_id = ? where id = ?", userId("claimant2.sg@demo.test"), c);
  }

  /** Status-keyed CHECKs: a closed or decided claim carries the figures that say so. */
  @Test
  @Transactional
  void statusAndAmountColumnsCannotDisagree() {
    UUID c = sqlClaimWithLedgerAndActivity();
    assertRejected("claim_approved_needs_amount", "update claim set status = 'APPROVED' where id = ?", c);
    assertRejected("claim_approved_needs_amount",
        "update claim set status = 'SETTLED', closed_at = now(), paid = 0 where id = ?", c);
    assertRejected("claim_rejected_needs_code", "update claim set status = 'REJECTED', closed_at = now() where id = ?", c);
    assertRejected("claim_closed_needs_closed_at",
        "update claim set status = 'REJECTED', rejection_code = 'OTHER' where id = ?", c);
    assertRejected("claim_closed_needs_closed_at",
        "update claim set status = 'SETTLED', approved_amount = 100, paid = 100 where id = ?", c);
    // and the consistent forms are accepted
    jdbc.update("update claim set status = 'APPROVED', approved_amount = 100 where id = ?", c);
    jdbc.update("update claim set status = 'SETTLED', closed_at = now(), paid = 100 where id = ?", c);
    jdbc.update("update claim set status = 'REJECTED', rejection_code = 'OTHER' where id = ?", c); // closed_at already set
  }

  @Test
  void truncateIsRefusedOnTheAppendOnlyTables() {
    assertThatThrownBy(() -> jdbc.execute("truncate financial_entry cascade"))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.execute("truncate claim_activity cascade"))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
  }
}
