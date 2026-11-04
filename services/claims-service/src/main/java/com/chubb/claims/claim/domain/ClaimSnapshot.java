package com.chubb.claims.claim.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** What the rules need to know about a claim, independent of how it is stored. */
public record ClaimSnapshot(
    ClaimStatus status, UUID policyholderId, String market, String currency, BigDecimal coverageLimit,
    UUID assignedOfficerId, BigDecimal approvedAmount) {

  ClaimContext context() {
    return new ClaimContext(policyholderId, market, assignedOfficerId);
  }
}
