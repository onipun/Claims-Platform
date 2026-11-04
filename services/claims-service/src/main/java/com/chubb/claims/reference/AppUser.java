package com.chubb.claims.reference;

import com.chubb.claims.claim.domain.Role;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser {
  @Id private UUID id;
  private String email;

  @Column(name = "password_hash")
  private String passwordHash;

  private String name;

  @Enumerated(EnumType.STRING)
  private Role role;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "user_market", joinColumns = @JoinColumn(name = "user_id"))
  @Column(name = "market_code")
  private Set<String> markets;

  /** Per market, in that market's currency. A market with a null limit has no row value here. */
  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "user_market", joinColumns = @JoinColumn(name = "user_id"))
  @MapKeyColumn(name = "market_code")
  @Column(name = "authority_limit")
  private Map<String, BigDecimal> authorityLimits;

  protected AppUser() {}

  public UUID getId() { return id; }
  public String getEmail() { return email; }
  public String getPasswordHash() { return passwordHash; }
  public String getName() { return name; }
  public Role getRole() { return role; }
  public Map<String, BigDecimal> getAuthorityLimits() { return authorityLimits; }
  public Set<String> getMarkets() { return markets; }
}
