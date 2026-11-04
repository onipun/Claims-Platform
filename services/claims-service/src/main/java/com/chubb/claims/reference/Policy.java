package com.chubb.claims.reference;

import com.chubb.claims.claim.domain.Line;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Seeded and read-only. */
@Entity
@Table(name = "policy")
public class Policy {
  @Id private UUID id;

  @Column(name = "policy_number")
  private String policyNumber;

  @Column(name = "holder_id")
  private UUID holderId;

  @Column(name = "market_code")
  private String marketCode;

  @Enumerated(EnumType.STRING)
  private Line line;

  @Column(name = "coverage_limit")
  private BigDecimal coverageLimit;

  private BigDecimal deductible;

  @Column(name = "effective_from")
  private LocalDate effectiveFrom;

  @Column(name = "effective_to")
  private LocalDate effectiveTo;

  protected Policy() {}

  public UUID getId() { return id; }
  public String getPolicyNumber() { return policyNumber; }
  public UUID getHolderId() { return holderId; }
  public String getMarketCode() { return marketCode; }
  public Line getLine() { return line; }
  public BigDecimal getCoverageLimit() { return coverageLimit; }
  public BigDecimal getDeductible() { return deductible; }
  public LocalDate getEffectiveFrom() { return effectiveFrom; }
  public LocalDate getEffectiveTo() { return effectiveTo; }
}
