package com.chubb.claims.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/** One row per successful command, written in the command's own transaction. */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_id")
  private UUID eventId;

  @Column(name = "claim_id")
  private UUID claimId;

  private long version;

  @Column(name = "event_type")
  private String eventType;

  @Column(columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String payload;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  protected OutboxEvent() {}

  OutboxEvent(UUID eventId, UUID claimId, long version, String eventType, String payload, Instant createdAt) {
    this.eventId = eventId;
    this.claimId = claimId;
    this.version = version;
    this.eventType = eventType;
    this.payload = payload;
    this.createdAt = createdAt;
  }

  public Long getId() { return id; }
  public UUID getEventId() { return eventId; }
  public UUID getClaimId() { return claimId; }
  public long getVersion() { return version; }
  public String getEventType() { return eventType; }
  public String getPayload() { return payload; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getPublishedAt() { return publishedAt; }
}
