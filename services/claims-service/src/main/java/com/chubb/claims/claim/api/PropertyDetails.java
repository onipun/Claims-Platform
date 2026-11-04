package com.chubb.claims.claim.api;

import com.chubb.claims.claim.domain.PropertyLoss;
import jakarta.validation.constraints.Size;

public record PropertyDetails(
    @com.chubb.claims.web.PlainText @Size(max = 500) String address, @com.chubb.claims.web.PlainText @Size(max = 200) String cause, @com.chubb.claims.web.PlainText @Size(max = 120) String occupancy) {

  public PropertyLoss toDomain() {
    return new PropertyLoss(address, cause, occupancy);
  }

  public static PropertyDetails from(PropertyLoss p) {
    return new PropertyDetails(p.address(), p.cause(), p.occupancy());
  }
}
