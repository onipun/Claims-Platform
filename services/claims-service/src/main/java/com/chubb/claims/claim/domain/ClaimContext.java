package com.chubb.claims.claim.domain;

import java.util.UUID;

public record ClaimContext(UUID policyholderId, String market, UUID assignedOfficerId) {}
