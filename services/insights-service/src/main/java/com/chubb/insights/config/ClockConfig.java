package com.chubb.insights.config;

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
@org.springframework.boot.context.properties.EnableConfigurationProperties(FxProperties.class)
class ClockConfig {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /** Money is sent as a JSON string, never a float, so no client rounds it. */
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
}
