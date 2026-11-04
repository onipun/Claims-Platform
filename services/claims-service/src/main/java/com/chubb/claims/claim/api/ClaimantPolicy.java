package com.chubb.claims.claim.api;

import com.chubb.claims.claim.domain.Line;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ClaimantPolicy(
    UUID id, String policyNumber, Line line, String marketCode, String currency, BigDecimal coverageLimit,
    BigDecimal deductible, LocalDate effectiveFrom, LocalDate effectiveTo) {}
