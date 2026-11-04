package com.chubb.claims.reference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.ZoneId;

@Entity
@Table(name = "market")
public class Market {
  @Id private String code;
  private String currency;
  private String region;

  @Column(name = "zone_id")
  private String zoneId;

  protected Market() {}

  public String getCode() { return code; }
  public String getCurrency() { return currency; }
  public String getRegion() { return region; }
  public ZoneId zone() { return ZoneId.of(zoneId); }
}
