package com.chubb.claims.reference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "document_requirement")
public class DocumentRequirement {
  @Id private UUID id;

  @Column(name = "market_code")
  private String marketCode;

  private String line;

  @Column(name = "claim_type")
  private String claimType;

  @Column(name = "document_type")
  private String documentType;

  /** Null for placeholder rows. */
  private String source;

  private boolean placeholder;

  protected DocumentRequirement() {}

  public String getMarketCode() { return marketCode; }
  public String getLine() { return line; }
  public String getClaimType() { return claimType; }
  public String getDocumentType() { return documentType; }
  public String getSource() { return source; }
  public boolean isPlaceholder() { return placeholder; }
}
