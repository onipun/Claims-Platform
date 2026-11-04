package com.chubb.claims.claim;

import com.chubb.claims.claim.domain.ClaimStatus;
import com.chubb.claims.claim.domain.Decision.Visibility;
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
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Append-only timeline; a database trigger also refuses UPDATE and DELETE. */
@Entity
@Immutable
@Table(name = "claim_activity")
public class ClaimActivity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "claim_id")
  private Claim claim;

  @Column(name = "activity_type")
  private String type;

  @Column(name = "actor_id")
  private UUID actorId;

  @Enumerated(EnumType.STRING)
  private Visibility visibility;

  private String message;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_status")
  private ClaimStatus fromStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_status")
  private ClaimStatus toStatus;

  @Column(name = "created_at")
  private Instant createdAt;

  protected ClaimActivity() {}

  public ClaimActivity(Claim claim, String type, UUID actorId, Visibility visibility, String message,
      ClaimStatus from, ClaimStatus to, Instant at) {
    this.claim = claim;
    this.type = type;
    this.actorId = actorId;
    this.visibility = visibility;
    this.message = message;
    this.fromStatus = from;
    this.toStatus = to;
    this.createdAt = at;
  }

  public Long getId() { return id; }
  public String getType() { return type; }
  public UUID getActorId() { return actorId; }
  public Visibility getVisibility() { return visibility; }
  public String getMessage() { return message; }
  public ClaimStatus getFromStatus() { return fromStatus; }
  public ClaimStatus getToStatus() { return toStatus; }
  public Instant getCreatedAt() { return createdAt; }
}
