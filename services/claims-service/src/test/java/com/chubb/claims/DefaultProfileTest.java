package com.chubb.claims;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.claims.support.ApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * I1: without the demo profile only reference data exists. No seeded account can log in, so a fresh
 * production database carries no known passwords.
 */
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration")
class DefaultProfileTest extends ApiTest {

  @Test
  void theDefaultProfileHasNoUsersPoliciesOrDemoClaims() {
    assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from user_market", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from policy", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from claim", Integer.class)).isZero();
    // reference data stays
    assertThat(jdbc.queryForObject("select count(*) from market", Integer.class)).isEqualTo(6);
    assertThat(jdbc.queryForObject("select count(*) from market_rule", Integer.class)).isPositive();
    assertThat(jdbc.queryForObject("select count(*) from document_requirement", Integer.class)).isPositive();
    assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '1000'", Integer.class))
        .as("the demo script was not applied").isZero();
  }

  @Test
  void aDemoAccountCannotLogIn() {
    Resp r = send("POST", "/api/v1/auth/login", null,
        "{\"email\":\"manager.apac@demo.test\",\"password\":\"" + PASSWORD + "\"}");
    assertThat(r.status()).as(r.body()).isEqualTo(401);
  }
}
