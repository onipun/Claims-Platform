package com.chubb.claims;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/**
 * Reads the real application.yml and application-demo.yml (no Spring context, no test property): the base
 * configuration must never load the seeded accounts, and the demo profile must.
 */
class DemoLocationsConfigTest {
  private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

  @Test
  void theBaseConfigurationDoesNotLoadTheDemoMigrations() throws Exception {
    var base = loader.load("base", new ClassPathResource("application.yml")).get(0);
    Object locations = base.getProperty("spring.flyway.locations");
    assertThat(String.valueOf(locations == null ? "" : locations)).doesNotContain("db/demo");
    for (String name : base.getSource() instanceof java.util.Map<?, ?> m ? m.keySet().stream().map(Object::toString).toList() : java.util.List.<String>of()) {
      assertThat(String.valueOf(base.getProperty(name))).as(name).doesNotContain("db/demo");
    }
  }

  @Test
  void theDemoProfileLoadsTheDemoMigrations() throws Exception {
    var demo = loader.load("demo", new ClassPathResource("application-demo.yml")).get(0);
    assertThat(String.valueOf(demo.getProperty("spring.flyway.locations"))).contains("db/demo").contains("db/migration");
  }
}
