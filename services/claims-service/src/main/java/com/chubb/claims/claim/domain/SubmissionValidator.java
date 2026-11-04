package com.chubb.claims.claim.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class SubmissionValidator {
  private SubmissionValidator() {}

  public static List<Violation> validate(SubmissionInput in, PolicyFacts policy, Clock clock) {
    List<Violation> out = new ArrayList<>();
    if (!policy.holderId().equals(in.claimantId())) {
      // Someone else's policy must look the same as a policy that does not exist.
      out.add(new Violation(ErrorCode.NOT_FOUND, "policyId", "Policy not found"));
      return out;
    }
    if (policy.line() != in.line()) {
      out.add(new Violation(ErrorCode.POLICY_MISMATCH, "policyId", "Policy line does not match the claim"));
    }
    if (!in.line().allows(in.claimType())) {
      out.add(new Violation(ErrorCode.INVALID_CLAIM_TYPE, "claimType",
          "Claim type is not valid for " + in.line()));
    }
    incident(in, policy, clock, out);
    try {
      AmountRules.submitEstimate(in.estimate(), policy.currency());
    } catch (DomainException e) {
      out.add(new Violation(e.code(), "estimate", e.getMessage()));
    }
    lossDetails(in, out);
    return out;
  }

  private static void incident(SubmissionInput in, PolicyFacts policy, Clock clock, List<Violation> out) {
    LocalDateTime at = in.incidentAtLocal();
    if (at == null) {
      out.add(new Violation(ErrorCode.VALIDATION_FAILED, "incidentAtLocal", "Incident time is required"));
      return;
    }
    LocalDateTime nowLocal = LocalDateTime.ofInstant(clock.instant(), policy.marketZone());
    if (at.isAfter(nowLocal)) {
      out.add(new Violation(ErrorCode.INCIDENT_IN_FUTURE, "incidentAtLocal", "Incident is in the future"));
    }
    LocalDate day = at.toLocalDate();
    if (day.isBefore(policy.effectiveFrom()) || day.isAfter(policy.effectiveTo())) {
      out.add(new Violation(ErrorCode.INCIDENT_OUTSIDE_POLICY_PERIOD, "incidentAtLocal",
          "Incident is outside the policy dates"));
    }
  }

  private static void lossDetails(SubmissionInput in, List<Violation> out) {
    if (in.line() == Line.MOTOR) {
      if (!(in.loss() instanceof MotorLoss m)) {
        out.add(missing("lossDetails", "Motor loss details are required"));
        return;
      }
      if (blank(m.vehicleRegistration())) out.add(missing("lossDetails.vehicleRegistration", "Vehicle registration is required"));
      if (blank(m.driverName())) out.add(missing("lossDetails.driverName", "Driver name is required"));
      if (blank(m.location())) out.add(missing("lossDetails.location", "Accident location is required"));
    } else {
      if (!(in.loss() instanceof PropertyLoss p)) {
        out.add(missing("lossDetails", "Property loss details are required"));
        return;
      }
      if (blank(p.address())) out.add(missing("lossDetails.address", "Loss address is required"));
      if (blank(p.cause())) out.add(missing("lossDetails.cause", "Cause of loss is required"));
    }
  }

  private static Violation missing(String field, String message) {
    return new Violation(ErrorCode.VALIDATION_FAILED, field, message);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
