package com.chubb.claims.claim.domain;

/** {@code location} is where the accident happened; required at submission, null on claims from before R12. {@code injuryDescription} is optional free text and is only stored; it is not tied to {@code injury}. */
public record MotorLoss(
    String vehicleRegistration, String driverName, ThirdParty thirdParty, boolean injury, String policeReport,
    String injuryDescription, String location)
    implements LossDetails {

  public MotorLoss(String vehicleRegistration, String driverName, ThirdParty thirdParty, boolean injury,
      String policeReport, String injuryDescription) {
    this(vehicleRegistration, driverName, thirdParty, injury, policeReport, injuryDescription, null);
  }

  public MotorLoss(String vehicleRegistration, String driverName, ThirdParty thirdParty, boolean injury,
      String policeReport) {
    this(vehicleRegistration, driverName, thirdParty, injury, policeReport, null, null);
  }
}
