package com.chubb.claims.claim.api;

import com.chubb.claims.claim.domain.Role;
import java.util.List;
import java.util.UUID;

/** For the assign dropdown: no email, no authority limit. Markets are those shared with the viewer. */
public record StaffOfficer(UUID id, String name, Role role, List<String> markets) {}
