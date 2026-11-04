package com.chubb.claims.claim.domain;

public record Violation(ErrorCode code, String field, String message) {}
