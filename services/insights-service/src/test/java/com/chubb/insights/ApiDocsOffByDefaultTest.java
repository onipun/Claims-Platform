package com.chubb.insights;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/**
 * The test setup turns the API docs on (src/test/resources/application.properties), so a context test cannot see
 * the default. This reads the real application.yml: off by default (this service has no demo profile).
 */
class ApiDocsOffByDefaultTest {
  private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

  @Test
  void theApplicationDefaultIsOff() throws Exception {
    var base = loader.load("base", new ClassPathResource("application.yml")).get(0);
    assertThat(base.getProperty("springdoc.api-docs.enabled")).isEqualTo(false);
    assertThat(base.getProperty("springdoc.swagger-ui.enabled")).isEqualTo(false);
  }
}
