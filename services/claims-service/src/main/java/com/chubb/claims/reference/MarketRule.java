package com.chubb.claims.reference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "market_rule")
public class MarketRule {
  @Id private UUID id;

  @Column(name = "market_code")
  private String marketCode;

  private String line;

  @Column(name = "rule_type")
  private String ruleType;

  @Column(columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String parameters;

  @Column(name = "source_url")
  private String sourceUrl;

  protected MarketRule() {}

  public String getMarketCode() { return marketCode; }
  public String getLine() { return line; }
  public String getRuleType() { return ruleType; }
  public String getParameters() { return parameters; }
  public String getSourceUrl() { return sourceUrl; }
}
