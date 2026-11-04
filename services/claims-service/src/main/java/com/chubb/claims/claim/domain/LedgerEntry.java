package com.chubb.claims.claim.domain;

import java.math.BigDecimal;

public record LedgerEntry(LedgerType type, BigDecimal amount) {}
