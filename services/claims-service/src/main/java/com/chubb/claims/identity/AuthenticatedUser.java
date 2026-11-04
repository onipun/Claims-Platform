package com.chubb.claims.identity;

import com.chubb.claims.claim.domain.Actor;
import com.chubb.claims.claim.domain.Role;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** The caller, read from a verified token. Authority limits are not in the token; staff load them. */
public record AuthenticatedUser(UUID id, Role role, Set<String> markets, String name) {

  public static AuthenticatedUser from(Jwt jwt) {
    List<String> markets = jwt.getClaimAsStringList("markets");
    return new AuthenticatedUser(UUID.fromString(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString("role")),
        markets == null ? Set.of() : Set.copyOf(new HashSet<>(markets)), jwt.getClaimAsString("name"));
  }

  public Actor toActor() {
    return new Actor(id, role, markets, Map.of());
  }
}
