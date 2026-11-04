package com.chubb.claims.claim.api;

import com.chubb.claims.claim.domain.ClaimStatus;
import java.time.Instant;
import java.util.UUID;

public record ClaimantUpdate(
    long id, UUID claimId, String claimNumber, String type, String message, ClaimStatus fromStatus,
    ClaimStatus toStatus, Instant at, String actorName) {}
