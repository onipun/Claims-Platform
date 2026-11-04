package com.chubb.claims.claim.api;

import com.chubb.claims.claim.domain.ClaimStatus;
import com.chubb.claims.claim.domain.ClaimType;
import com.chubb.claims.claim.domain.Line;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

public record ClaimantClaimSummary(
    UUID id, String claimNumber, Line line, ClaimType claimType, ClaimStatus status,
    LocalDateTime incidentAtLocal, Instant reportedAt, String currency, BigDecimal estimate,
    boolean late, boolean actionRequired) {}
