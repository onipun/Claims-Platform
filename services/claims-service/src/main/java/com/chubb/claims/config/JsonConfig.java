package com.chubb.claims.config;

import java.math.BigDecimal;
import java.time.Clock;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

@Configuration(proxyBeanMethods = false)
class JsonConfig {

  /** Trap 8: money is sent as a JSON string, never a float, so no client rounds it. */
  @Bean
  JsonMapperBuilderCustomizer moneyAsString() {
    SimpleModule module = new SimpleModule("money-as-string");
    module.addSerializer(BigDecimal.class, new ValueSerializer<BigDecimal>() {
      @Override
      public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
        gen.writeString(value.toPlainString());
      }
    });
    return builder -> builder.addModule(module);
  }

  @Bean
  OffsetClock clock() {
    // Microsecond ticks: what we return equals what Postgres stores, so a replayed response is identical.
    // The offset is zero except while the demo-profile seeder back-dates a command (see OffsetClock).
    return new OffsetClock(Clock.tick(Clock.systemUTC(), java.time.Duration.ofNanos(1000)));
  }
}
