package com.chubb.claims.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "info_request")
public class InfoRequest {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "claim_id")
  private Claim claim;

  private String question;

  @Column(name = "asked_by")
  private UUID askedBy;

  @Column(name = "asked_at")
  private Instant askedAt;

  private String answer;

  @Column(name = "answered_by")
  private UUID answeredBy;

  @Column(name = "answered_at")
  private Instant answeredAt;

  protected InfoRequest() {}

  InfoRequest(Claim claim, String question, UUID askedBy, Instant at) {
    this.id = UUID.randomUUID();
    this.claim = claim;
    this.question = question;
    this.askedBy = askedBy;
    this.askedAt = at;
  }

  void answer(String answer, UUID by, Instant at) {
    this.answer = answer;
    this.answeredBy = by;
    this.answeredAt = at;
  }

  public boolean isOpen() { return answeredAt == null; }
  public UUID getId() { return id; }
  public String getQuestion() { return question; }
  public String getAnswer() { return answer; }
  public Instant getAskedAt() { return askedAt; }
  public Instant getAnsweredAt() { return answeredAt; }
}
