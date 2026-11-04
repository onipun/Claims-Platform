package com.chubb.claims.claim.api;

import com.chubb.claims.claim.domain.ClaimType;
import com.chubb.claims.claim.domain.Line;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Incident time is a local date-time ("2026-03-07T22:30:00") read in the policy market's zone.
 * Send {@code motor} for MOTOR and {@code property} for PROPERTY.
 */
public record SubmitClaimRequest(
    @NotNull UUID policyId,
    @NotNull Line line,
    @NotNull ClaimType claimType,
    @NotNull LocalDateTime incidentAtLocal,
    @NotNull @Digits(integer = 15, fraction = 2) BigDecimal estimate,
    @com.chubb.claims.web.PlainText @Size(max = 32) String contactPhone,
    @com.chubb.claims.web.PlainText @Size(max = 4000) String description,
    @com.chubb.claims.web.PlainText @Size(max = 1000) String lateReason,
    @Valid MotorDetails motor,
    @Valid PropertyDetails property) {}
