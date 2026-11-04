package com.chubb.claims.claim.api;

import com.chubb.claims.claim.domain.ClaimStatus;
import com.chubb.claims.claim.domain.ClaimType;
import com.chubb.claims.claim.domain.Line;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One row of a staff queue. {@code overdue} is a pick-up or decision deadline passed; see StaffClaimMapper. */
public record StaffClaimSummary(
    UUID id, String claimNumber, String market, Line line, ClaimType claimType, ClaimStatus status,
    String currency, BigDecimal estimate, Instant reportedAt, Instant pickupDueAt, Instant decisionDueAt,
    boolean overdue, boolean waitingOnClaimant, boolean late, String assignedOfficerName, long version) {}
