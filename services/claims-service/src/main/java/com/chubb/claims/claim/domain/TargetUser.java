package com.chubb.claims.claim.domain;

import java.util.Set;
import java.util.UUID;

public record TargetUser(UUID id, Role role, Set<String> markets) {}
