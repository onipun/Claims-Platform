package com.chubb.claims.claim;

import com.chubb.claims.claim.domain.ClaimDecider;
import com.chubb.claims.claim.domain.ClaimSnapshot;
import com.chubb.claims.claim.domain.ClaimStatus;
import com.chubb.claims.claim.domain.ClaimType;
import com.chubb.claims.claim.domain.Command;
import com.chubb.claims.claim.domain.Decision;
import com.chubb.claims.claim.domain.FinancialPosition;
import com.chubb.claims.claim.domain.LedgerEntry;
import com.chubb.claims.claim.domain.LedgerType;
import com.chubb.claims.claim.domain.Line;
import com.chubb.claims.claim.domain.LossDetails;
import com.chubb.claims.claim.domain.Money;
import com.chubb.claims.claim.domain.ProvisionalReserve;
import com.chubb.claims.claim.domain.RejectReason;
import com.chubb.claims.claim.domain.ReserveStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * The aggregate. Status and money change only through {@link #execute}, which runs the shared
 * rules ({@link ClaimDecider}) and applies the result, so there is no public status setter.
 *
 * <p>Trap 2: every command sets {@code updatedAt}, so the row is dirty and the version bumps even
 * when only the timeline changes. Trap 1: Hibernate assigns the new version at flush, so build the
 * outbox payload after {@code saveAndFlush}.
 */
@Entity
@Table(name = "claim")
public class Claim {

  /** Everything the submission service resolves before a claim exists. */
  public record NewClaim(
      String claimNumber, UUID policyId, UUID claimantId, String marketCode, Line line, ClaimType claimType,
      LocalDateTime incidentAtLocal, String marketZone, Instant reportedAt, boolean late, String lateReason,
      String contactPhone, String description, LossDetails lossDetails, BigDecimal estimate,
      BigDecimal coverageLimit, BigDecimal deductible, String currency, Instant pickupDueAt,
      Instant decisionDueAt, String idempotencyKey, String requestHash) {}

  @Id private UUID id;

  @Column(name = "claim_number")
  private String claimNumber;

  @Column(name = "policy_id")
  private UUID policyId;

  @Column(name = "claimant_id")
  private UUID claimantId;

  @Column(name = "market_code")
  private String marketCode;

  @Enumerated(EnumType.STRING)
  private Line line;

  @Enumerated(EnumType.STRING)
  @Column(name = "claim_type")
  private ClaimType claimType;

  @Column(name = "reporting_channel")
  private String reportingChannel = "ONLINE";

  @Column(name = "incident_at_local")
  private LocalDateTime incidentAtLocal;

  @Column(name = "market_zone")
  private String marketZone;

  @Column(name = "reported_at")
  private Instant reportedAt;

  private boolean late;

  @Column(name = "late_reason")
  private String lateReason;

  @Column(name = "contact_phone")
  private String contactPhone;

  private String description;

  @Convert(converter = LossDetailsConverter.class)
  @Column(name = "loss_details", columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private LossDetails lossDetails;

  private BigDecimal estimate;

  @Enumerated(EnumType.STRING)
  private ClaimStatus status;

  @Column(name = "assigned_officer_id")
  private UUID assignedOfficerId;

  private BigDecimal reserve;

  @Enumerated(EnumType.STRING)
  @Column(name = "reserve_status")
  private ReserveStatus reserveStatus;

  private BigDecimal paid;

  @Column(name = "approved_amount")
  private BigDecimal approvedAmount;

  @Enumerated(EnumType.STRING)
  @Column(name = "rejection_code")
  private RejectReason rejectionCode;

  @Column(name = "rejection_explanation")
  private String rejectionExplanation;

  @Column(name = "coverage_limit")
  private BigDecimal coverageLimit;

  private BigDecimal deductible;
  private String currency;

  @Column(name = "pickup_due_at")
  private Instant pickupDueAt;

  @Column(name = "decision_due_at")
  private Instant decisionDueAt;

  @Column(name = "first_picked_up_at")
  private Instant firstPickedUpAt;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Column(name = "idempotency_key")
  private String idempotencyKey;

  /** SHA-256 of the canonicalised submit request; a replay with another body is refused. */
  @Column(name = "request_hash")
  private String requestHash;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Version private Long version;

  @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL)
  @OrderBy("id")
  private List<FinancialEntry> ledger = new ArrayList<>();

  @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL)
  @OrderBy("id")
  private List<ClaimActivity> activities = new ArrayList<>();

  @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL)
  @OrderBy("askedAt")
  private List<InfoRequest> infoRequests = new ArrayList<>();

  protected Claim() {}

  /** Creates the claim with its provisional reserve and the first timeline entry. */
  public static Claim submit(NewClaim n, Instant now) {
    com.chubb.claims.claim.domain.AmountRules.submitEstimate(n.estimate(), n.currency());
    Claim c = new Claim();
    c.id = UUID.randomUUID();
    c.claimNumber = n.claimNumber();
    c.policyId = n.policyId();
    c.claimantId = n.claimantId();
    c.marketCode = n.marketCode();
    c.line = n.line();
    c.claimType = n.claimType();
    c.incidentAtLocal = n.incidentAtLocal();
    c.marketZone = n.marketZone();
    c.reportedAt = n.reportedAt();
    c.late = n.late();
    c.lateReason = n.lateReason();
    c.contactPhone = n.contactPhone();
    c.description = n.description();
    c.lossDetails = n.lossDetails();
    c.estimate = Money.normalize(n.estimate(), n.currency());
    c.coverageLimit = n.coverageLimit();
    c.deductible = n.deductible();
    c.currency = n.currency();
    c.pickupDueAt = n.pickupDueAt();
    c.decisionDueAt = n.decisionDueAt();
    c.idempotencyKey = n.idempotencyKey();
    c.requestHash = n.requestHash();
    c.status = ClaimStatus.SUBMITTED;
    c.reserveStatus = ReserveStatus.PROVISIONAL;
    c.paid = Money.normalize(BigDecimal.ZERO, n.currency());
    BigDecimal provisional = Money.normalize(
        ProvisionalReserve.compute(n.estimate(), n.deductible(), n.coverageLimit()), n.currency());
    c.reserve = provisional;
    c.ledger.add(new FinancialEntry(c, new LedgerEntry(LedgerType.RESERVE_SET, provisional),
        n.claimantId(), "Provisional reserve", now));
    c.activities.add(new ClaimActivity(c, "SUBMITTED", n.claimantId(), Decision.Visibility.CLAIMANT,
        "Claim submitted", null, ClaimStatus.SUBMITTED, now));
    c.updatedAt = now;
    return c;
  }

  /** LIFECYCLE.md step 2. Services call this before the If-Match check. */
  public void checkAccess(Command command) {
    ClaimDecider.checkAccess(snapshot(), command);
  }

  /** Runs the command through the rules and applies the outcome. Throws DomainException. */
  public void execute(Command command, Instant now) {
    Decision d = ClaimDecider.decide(snapshot(), command);
    ClaimStatus from = status;

    status = d.status();
    assignedOfficerId = d.assignedOfficerId();
    approvedAmount = d.approvedAmount();
    if (d.reserveStatus() != null) reserveStatus = d.reserveStatus();
    if (d.ledgerEntry() != null) {
      ledger.add(new FinancialEntry(this, d.ledgerEntry(), command.actor().id(), d.message(), now));
      if (d.ledgerEntry().type() == LedgerType.RESERVE_SET) reserve = d.ledgerEntry().amount();
      else paid = paid.add(d.ledgerEntry().amount());
    }
    switch (d.infoChange()) {
      case OPEN -> infoRequests.add(new InfoRequest(this, d.message(), command.actor().id(), now));
      case ANSWER -> infoRequests.stream().filter(InfoRequest::isOpen).findFirst()
          .ifPresent(r -> r.answer(d.message(), command.actor().id(), now));
      case NONE -> { }
    }
    if (d.rejectReason() != null) {
      rejectionCode = d.rejectReason();
      rejectionExplanation = d.rejectionExplanation();
    }
    switch (d.action()) {
      case PICK_UP, ASSIGN -> { if (firstPickedUpAt == null) firstPickedUpAt = now; }
      case APPROVE -> decidedAt = now;
      case REJECT -> { decidedAt = now; closedAt = now; }
      case SETTLE -> closedAt = now;
      default -> { }
    }
    activities.add(new ClaimActivity(this, d.action().name(), command.actor().id(), d.visibility(),
        d.message(), from, status, now));
    touch(now);
  }

  /** A strictly newer timestamp guarantees a dirty row, so the version bumps on every command. */
  private void touch(Instant now) {
    updatedAt = (updatedAt != null && !now.isAfter(updatedAt)) ? updatedAt.plusNanos(1_000) : now;
  }

  private ClaimSnapshot snapshot() {
    return new ClaimSnapshot(status, claimantId, marketCode, currency, coverageLimit, assignedOfficerId,
        approvedAmount);
  }

  public FinancialPosition position() {
    return FinancialPosition.from(ledger.stream().map(FinancialEntry::toLedgerEntry).toList());
  }

  public UUID getId() { return id; }
  public String getClaimNumber() { return claimNumber; }
  public UUID getPolicyId() { return policyId; }
  public UUID getClaimantId() { return claimantId; }
  public String getMarketCode() { return marketCode; }
  public Line getLine() { return line; }
  public ClaimType getClaimType() { return claimType; }
  public String getReportingChannel() { return reportingChannel; }
  public LocalDateTime getIncidentAtLocal() { return incidentAtLocal; }
  public String getMarketZone() { return marketZone; }
  public Instant getReportedAt() { return reportedAt; }
  public boolean isLate() { return late; }
  public String getLateReason() { return lateReason; }
  public String getContactPhone() { return contactPhone; }
  public String getDescription() { return description; }
  public LossDetails getLossDetails() { return lossDetails; }
  public BigDecimal getEstimate() { return estimate; }
  public ClaimStatus getStatus() { return status; }
  public UUID getAssignedOfficerId() { return assignedOfficerId; }
  public BigDecimal getReserve() { return reserve; }
  public ReserveStatus getReserveStatus() { return reserveStatus; }
  public BigDecimal getPaid() { return paid; }
  public BigDecimal getApprovedAmount() { return approvedAmount; }
  public RejectReason getRejectionCode() { return rejectionCode; }
  public String getRejectionExplanation() { return rejectionExplanation; }
  public BigDecimal getCoverageLimit() { return coverageLimit; }
  public BigDecimal getDeductible() { return deductible; }
  public String getCurrency() { return currency; }
  public Instant getPickupDueAt() { return pickupDueAt; }
  public Instant getDecisionDueAt() { return decisionDueAt; }
  public Instant getFirstPickedUpAt() { return firstPickedUpAt; }
  public Instant getDecidedAt() { return decidedAt; }
  public Instant getClosedAt() { return closedAt; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public String getRequestHash() { return requestHash; }
  public Instant getUpdatedAt() { return updatedAt; }
  public Long getVersion() { return version; }
  public List<FinancialEntry> getLedger() { return Collections.unmodifiableList(ledger); }
  public List<ClaimActivity> getActivities() { return Collections.unmodifiableList(activities); }
  public List<InfoRequest> getInfoRequests() { return Collections.unmodifiableList(infoRequests); }
}
