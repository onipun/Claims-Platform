package com.chubb.claims.claim.api;

import com.chubb.claims.claim.domain.MotorLoss;
import com.chubb.claims.claim.domain.ThirdParty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * Motor-specific part of a claim, in both directions. Required fields are enforced by
 * SubmissionValidator. {@code injury} is a Boolean so that leaving it out means false, not a 400.
 */
public record MotorDetails(
    @com.chubb.claims.web.PlainText @Size(max = 32) String vehicleRegistration,
    @com.chubb.claims.web.PlainText @Size(max = 120) String driverName,
    @Valid ThirdPartyDetails thirdParty,
    Boolean injury,
    @com.chubb.claims.web.PlainText @Size(max = 64) String policeReport,
    @com.chubb.claims.web.PlainText @Size(max = 2000) String injuryDescription,
    @com.chubb.claims.web.PlainText @Size(max = 200) String location) {

  public record ThirdPartyDetails(
      @com.chubb.claims.web.PlainText @Size(max = 120) String name, @com.chubb.claims.web.PlainText @Size(max = 32) String phone,
      @com.chubb.claims.web.PlainText @Size(max = 120) String insurer, @com.chubb.claims.web.PlainText @Size(max = 32) String vehicleNumber) {}

  public MotorDetails(String vehicleRegistration, String driverName, ThirdPartyDetails thirdParty, Boolean injury,
      String policeReport, String injuryDescription) {
    this(vehicleRegistration, driverName, thirdParty, injury, policeReport, injuryDescription, null);
  }

  public MotorLoss toDomain() {
    ThirdParty tp = thirdParty == null ? null
        : new ThirdParty(thirdParty.name(), thirdParty.phone(), thirdParty.insurer(), thirdParty.vehicleNumber());
    return new MotorLoss(vehicleRegistration, driverName, tp, Boolean.TRUE.equals(injury), policeReport,
        injuryDescription, location);
  }

  public static MotorDetails from(MotorLoss m) {
    ThirdParty t = m.thirdParty();
    return new MotorDetails(m.vehicleRegistration(), m.driverName(),
        t == null ? null : new ThirdPartyDetails(t.name(), t.phone(), t.insurer(), t.vehicleNumber()),
        m.injury(), m.policeReport(), m.injuryDescription(), m.location());
  }
}
