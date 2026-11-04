package com.chubb.claims.claim.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record SubmissionInput(UUID claimantId, Line line, ClaimType claimType, LocalDateTime incidentAtLocal,
    BigDecimal estimate, LossDetails loss, String contactPhone, String description) {}
