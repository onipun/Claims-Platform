package com.chubb.claims.reference;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketRuleRepository extends JpaRepository<MarketRule, UUID> {
  boolean existsByMarketCodeAndLineAndRuleType(String marketCode, String line, String ruleType);
}
