package com.chubb.claims.claim.domain;

import java.util.EnumSet;
import java.util.Set;

public enum Line {
  MOTOR(EnumSet.of(ClaimType.OWN_DAMAGE, ClaimType.THIRD_PARTY_DAMAGE, ClaimType.TOTAL_LOSS,
      ClaimType.WINDSCREEN, ClaimType.THEFT)),
  PROPERTY(EnumSet.of(ClaimType.FIRE, ClaimType.WATER_DAMAGE, ClaimType.STORM, ClaimType.THEFT,
      ClaimType.OTHER));

  private final Set<ClaimType> claimTypes;

  Line(Set<ClaimType> claimTypes) {
    this.claimTypes = claimTypes;
  }

  public boolean allows(ClaimType type) {
    return type != null && claimTypes.contains(type);
  }
}
