package com.chubb.claims.claim;

import com.chubb.claims.claim.api.MotorDetails;
import com.chubb.claims.claim.api.PropertyDetails;
import com.chubb.claims.claim.api.StaffClaimDetail;
import com.chubb.claims.claim.api.StaffClaimDetail.Deadlines;
import com.chubb.claims.claim.api.StaffClaimDetail.InfoRequestView;
import com.chubb.claims.claim.api.StaffClaimDetail.Ledger;
import com.chubb.claims.claim.api.StaffClaimDetail.OpenQuestion;
import com.chubb.claims.claim.api.StaffClaimDetail.Person;
import com.chubb.claims.claim.api.StaffClaimDetail.Rejection;
import com.chubb.claims.claim.api.StaffClaimDetail.TimelineEntry;
import com.chubb.claims.claim.api.StaffClaimSummary;
import com.chubb.claims.claim.api.StaffFinancials;
import com.chubb.claims.claim.domain.Actor;
import com.chubb.claims.claim.domain.ActorPermissions;
import com.chubb.claims.claim.domain.ClaimAction;
import com.chubb.claims.claim.domain.ClaimContext;
import com.chubb.claims.claim.domain.ClaimStatus;
import com.chubb.claims.claim.domain.FinancialPosition;
import com.chubb.claims.claim.domain.MotorLoss;
import com.chubb.claims.claim.domain.Money;
import com.chubb.claims.claim.domain.PermissionResult;
import com.chubb.claims.claim.domain.PropertyLoss;
import com.chubb.claims.claim.domain.Role;
import com.chubb.claims.claim.domain.TargetUser;
import com.chubb.claims.claim.domain.Transitions;
import com.chubb.claims.reference.AppUser;
import com.chubb.claims.reference.AppUserRepository;
import com.chubb.claims.reference.Policy;
import com.chubb.claims.reference.PolicyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The only place a Claim becomes something staff see. Call inside a transaction (lazy collections). */
@Component
class StaffClaimMapper {
  private final PolicyRepository policies;
  private final AppUserRepository users;
  private final Clock clock;

  StaffClaimMapper(PolicyRepository policies, AppUserRepository users, Clock clock) {
    this.policies = policies;
    this.users = users;
    this.clock = clock;
  }

  Map<UUID, String> names(Collection<UUID> ids) {
    Map<UUID, String> out = new HashMap<>();
    Set<UUID> wanted = new HashSet<>();
    for (UUID id : ids) if (id != null) wanted.add(id);
    if (wanted.isEmpty()) return out;
    for (AppUser u : users.findAllById(wanted)) out.put(u.getId(), u.getName());
    return out;
  }

  /** Pick-up deadline passed with nobody assigned, or decision deadline passed on an undecided claim. */
  boolean overdue(Claim c) {
    Instant now = clock.instant();
    boolean pickupOverdue = c.getFirstPickedUpAt() == null && now.isAfter(c.getPickupDueAt());
    boolean decisionOverdue = c.getDecidedAt() == null && c.getClosedAt() == null && now.isAfter(c.getDecisionDueAt());
    return pickupOverdue || decisionOverdue;
  }

  StaffClaimSummary summary(Claim c, Map<UUID, String> names) {
    return new StaffClaimSummary(c.getId(), c.getClaimNumber(), c.getMarketCode(), c.getLine(), c.getClaimType(),
        c.getStatus(), c.getCurrency(), c.getEstimate(), c.getReportedAt(), c.getPickupDueAt(),
        c.getDecisionDueAt(), overdue(c), c.getStatus() == ClaimStatus.AWAITING_INFO, c.isLate(),
        names.get(c.getAssignedOfficerId()), c.getVersion());
  }

  StaffClaimDetail detail(Claim c, Actor viewer) {
    Set<UUID> ids = new HashSet<>();
    ids.add(c.getClaimantId());
    ids.add(c.getAssignedOfficerId());
    c.getActivities().forEach(a -> ids.add(a.getActorId()));
    Map<UUID, String> names = names(ids);

    List<TimelineEntry> timeline = c.getActivities().stream()
        .map(a -> new TimelineEntry(a.getType(), a.getVisibility().name(), a.getMessage(), a.getFromStatus(),
            a.getToStatus(), a.getCreatedAt(), names.get(a.getActorId())))
        .toList();
    InfoRequest open = c.getInfoRequests().stream().filter(InfoRequest::isOpen).findFirst().orElse(null);
    FinancialPosition pos = normalised(c);
    String policyNumber = policies.findById(c.getPolicyId()).map(Policy::getPolicyNumber).orElse(null);
    boolean waiting = c.getStatus() == ClaimStatus.AWAITING_INFO;

    return new StaffClaimDetail(c.getId(), c.getClaimNumber(), c.getMarketCode(), c.getPolicyId(), policyNumber,
        c.getLine(), c.getClaimType(), c.getStatus(), c.getIncidentAtLocal(), c.getMarketZone(),
        c.getReportedAt(), c.isLate(), c.getLateReason(), c.getContactPhone(), c.getDescription(),
        c.getCurrency(), c.getEstimate(),
        c.getLossDetails() instanceof MotorLoss m ? MotorDetails.from(m) : null,
        c.getLossDetails() instanceof PropertyLoss p ? PropertyDetails.from(p) : null,
        new Person(c.getClaimantId(), names.get(c.getClaimantId())),
        c.getAssignedOfficerId() == null ? null
            : new Person(c.getAssignedOfficerId(), names.get(c.getAssignedOfficerId())),
        new Ledger(c.getCoverageLimit(), c.getDeductible(), pos.reserve(), c.getReserveStatus(), pos.paid(),
            pos.outstanding(), pos.incurred(), c.getApprovedAmount()),
        new Deadlines(c.getPickupDueAt(), c.getDecisionDueAt(), c.getFirstPickedUpAt(), c.getDecidedAt(),
            c.getClosedAt(), overdue(c), waiting),
        open == null ? null : new OpenQuestion(open.getQuestion(), open.getAskedAt()),
        c.getStatus() == ClaimStatus.REJECTED ? new Rejection(c.getRejectionCode(), c.getRejectionExplanation()) : null,
        c.getInfoRequests().stream()
            .map(r -> new InfoRequestView(r.getQuestion(), r.getAskedAt(), r.getAnswer(), r.getAnsweredAt())).toList(),
        timeline, allowedActions(viewer, c), viewer.authorityLimit(c.getMarketCode()), c.getVersion());
  }

  /**
   * Actions this viewer could attempt right now: the transition exists and the permission check
   * passes. Amount-dependent limits (APPROVE) are checked against the real amount on submit.
   */
  static List<String> allowedActions(Actor viewer, Claim c) {
    ClaimContext ctx = new ClaimContext(c.getClaimantId(), c.getMarketCode(), c.getAssignedOfficerId());
    TargetUser anyColleague = new TargetUser(UUID.randomUUID(), Role.OFFICER, Set.of(c.getMarketCode()));
    List<String> out = new ArrayList<>();
    for (ClaimAction a : ClaimAction.values()) {
      if (a == ClaimAction.ANSWER_INFO) continue;
      if (Transitions.next(c.getStatus(), a).isEmpty()) continue;
      if (ActorPermissions.check(viewer, ctx, a, a == ClaimAction.ASSIGN ? anyColleague : null)
          instanceof PermissionResult.Allowed) {
        out.add(a.name());
      }
    }
    return out;
  }

  /** The ledger sums start from zero with no scale; show them in the currency's scale (IDR 0, others 2). */
  private static FinancialPosition normalised(Claim c) {
    FinancialPosition p = c.position();
    return new FinancialPosition(Money.normalize(p.reserve(), c.getCurrency()), Money.normalize(p.paid(), c.getCurrency()));
  }

  StaffFinancials financials(Claim c) {
    Map<UUID, String> names = names(c.getLedger().stream().map(FinancialEntry::getActorId).toList());
    FinancialPosition pos = normalised(c);
    return new StaffFinancials(c.getCurrency(), c.getCoverageLimit(), c.getDeductible(), c.getReserveStatus(),
        pos.reserve(), pos.paid(), pos.outstanding(), pos.incurred(), c.getApprovedAmount(),
        c.getLedger().stream().map(e -> new StaffFinancials.Entry(e.getId(), e.getType(), e.getAmount(),
            e.getReason(), names.get(e.getActorId()), e.getCreatedAt())).toList(),
        c.getVersion());
  }
}
