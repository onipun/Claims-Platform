package com.chubb.claims.claim.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The authority limits are per market, in that market's currency. A market the actor has a
 * {@code user_market} row for is a key of the map; its value is the limit, and a null value is
 * "unlimited" for a manager and "no authority" for anyone else. A market with no key means the actor
 * has no row there at all: no authority, whatever the role (a manager removed from a market). The map's
 * {@code get} can't tell those two apart, so callers ask {@link #hasRowIn} first.
 */
public record Actor(UUID id, Role role, Set<String> markets, Map<String, BigDecimal> authorityLimits) {

  public Actor {
    authorityLimits = authorityLimits == null ? Map.of()
        : java.util.Collections.unmodifiableMap(new java.util.HashMap<>(authorityLimits));
  }

  /** The same limit (or none, when null) in every one of the actor's markets. */
  public Actor(UUID id, Role role, Set<String> markets, BigDecimal limitInEveryMarket) {
    this(id, role, markets, everyMarket(markets, limitInEveryMarket));
  }

  private static Map<String, BigDecimal> everyMarket(Set<String> markets, BigDecimal limit) {
    Map<String, BigDecimal> out = new java.util.HashMap<>();
    markets.forEach(m -> out.put(m, limit));
    return out;
  }

  public boolean hasRowIn(String market) {
    return authorityLimits.containsKey(market);
  }

  public BigDecimal authorityLimit(String market) {
    return authorityLimits.get(market);
  }
}
