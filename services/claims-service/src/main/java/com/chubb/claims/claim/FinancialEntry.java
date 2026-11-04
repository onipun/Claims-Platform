package com.chubb.claims.claim;

import com.chubb.claims.claim.domain.LedgerEntry;
import com.chubb.claims.claim.domain.LedgerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Append-only; a database trigger also refuses UPDATE and DELETE. */
@Entity
@Immutable
@Table(name = "financial_entry")
public class FinancialEntry {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "claim_id")
  private Claim claim;

  @Enumerated(EnumType.STRING)
  @Column(name = "entry_type")
  private LedgerType type;

  private BigDecimal amount;

  private String currency;

  @Column(name = "actor_id")
  private UUID actorId;

  private String reason;

  @Column(name = "created_at")
  private Instant createdAt;

  protected FinancialEntry() {}

  FinancialEntry(Claim claim, LedgerEntry entry, UUID actorId, String reason, Instant at) {
    this.claim = claim;
    this.currency = claim.getCurrency();
    this.type = entry.type();
    this.amount = entry.amount();
    this.actorId = actorId;
    this.reason = reason;
    this.createdAt = at;
  }

  public LedgerEntry toLedgerEntry() { return new LedgerEntry(type, amount); }
  public Long getId() { return id; }
  public LedgerType getType() { return type; }
  public BigDecimal getAmount() { return amount; }
  public String getCurrency() { return currency; }
  public UUID getActorId() { return actorId; }
  public String getReason() { return reason; }
  public Instant getCreatedAt() { return createdAt; }
}
