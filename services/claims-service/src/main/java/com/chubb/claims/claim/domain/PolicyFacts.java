package com.chubb.claims.claim.domain;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

public record PolicyFacts(UUID holderId, Line line, String currency, ZoneId marketZone,
    LocalDate effectiveFrom, LocalDate effectiveTo) {}
