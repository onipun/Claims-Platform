package com.chubb.claims.claim;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Placeholder service levels, not sourced (LIFECYCLE.md, Service levels). */
@ConfigurationProperties("app.sla")
public record SlaProperties(int pickupHours, int decisionDays) {
  public SlaProperties {
    if (pickupHours <= 0) pickupHours = 24;
    if (decisionDays <= 0) decisionDays = 14;
  }
}
