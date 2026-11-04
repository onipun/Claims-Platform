package com.chubb.insights.config;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Fixed, indicative rates: USD per one unit of the currency. Never used for a decision (D9). */
@ConfigurationProperties("app.fx")
public record FxProperties(Map<String, BigDecimal> usdPerUnit) {
  public FxProperties {
    usdPerUnit = usdPerUnit == null ? Map.of() : Map.copyOf(usdPerUnit);
  }
}
