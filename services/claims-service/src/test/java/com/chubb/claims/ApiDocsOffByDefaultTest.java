package com.chubb.claims;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.claims.support.ApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Without a profile that turns them on (demo does; the test setup does), the API docs are not served.
 * The properties below are the defaults from application.yml, restored over the test setup.
 */
@TestPropertySource(properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
class ApiDocsOffByDefaultTest extends ApiTest {

  @Test
  void theDocsPathsAreNotServed() {
    for (String path : new String[] {"/v3/api-docs", "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/index.html"}) {
      assertThat(get(path, null).status()).as(path).isIn(401, 404);
    }
  }

  @Test
  void theApplicationDefaultIsOffAndTheDemoProfileTurnsItOn() throws Exception {
    var loader = new org.springframework.boot.env.YamlPropertySourceLoader();
    var base = loader.load("base", new org.springframework.core.io.ClassPathResource("application.yml")).get(0);
    var demo = loader.load("demo", new org.springframework.core.io.ClassPathResource("application-demo.yml")).get(0);
    assertThat(base.getProperty("springdoc.api-docs.enabled")).isEqualTo(false);
    assertThat(base.getProperty("springdoc.swagger-ui.enabled")).isEqualTo(false);
    assertThat(demo.getProperty("springdoc.api-docs.enabled")).isEqualTo(true);
  }
}
