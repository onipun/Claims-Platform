package com.chubb.claims.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** keyDir comes from APP_JWT_KEY_DIR; tokens last one hour. */
@ConfigurationProperties("app.jwt")
public record JwtProperties(String keyDir, String issuer, Duration ttl) {
  public JwtProperties {
    if (issuer == null) issuer = "claims-service";
    if (ttl == null) ttl = Duration.ofHours(1);
  }
}
