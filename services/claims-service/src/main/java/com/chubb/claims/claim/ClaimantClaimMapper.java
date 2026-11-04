package com.chubb.claims.claim;

import com.chubb.claims.claim.api.ClaimantClaimDetail;
import com.chubb.claims.claim.api.ClaimantClaimDetail.DecisionView;
import com.chubb.claims.claim.api.ClaimantClaimDetail.OpenQuestion;
import com.chubb.claims.claim.api.ClaimantClaimDetail.TimelineEntry;
import com.chubb.claims.claim.api.ClaimantClaimSummary;
import com.chubb.claims.claim.api.MotorDetails;
import com.chubb.claims.claim.api.PropertyDetails;
import com.chubb.claims.claim.domain.ClaimStatus;
import com.chubb.claims.claim.domain.Decision.Visibility;
import com.chubb.claims.claim.domain.MotorLoss;
import com.chubb.claims.claim.domain.PropertyLoss;
import com.chubb.claims.reference.AppUser;
import com.chubb.claims.reference.AppUserRepository;
import com.chubb.claims.reference.Policy;
import com.chubb.claims.reference.PolicyRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The only place a Claim becomes something a claimant sees. Call inside a transaction (the
 * collections are lazy). Only CLAIMANT-visible activities are read.
 */
@Component
class ClaimantClaimMapper {
  private final PolicyRepository policies;
  private final AppUserRepository users;

  ClaimantClaimMapper(PolicyRepository policies, AppUserRepository users) {
    this.policies = policies;
    this.users = users;
  }

  /** Display names for the given actor ids; nothing else about them is read. */
  Map<UUID, String> names(Collection<UUID> ids) {
    Map<UUID, String> out = new HashMap<>();
    if (ids.isEmpty()) return out;
    for (AppUser u : users.findAllById(new HashSet<>(ids))) out.put(u.getId(), u.getName());
    return out;
  }

  ClaimantClaimDetail detail(Claim c) {
    List<ClaimActivity> visible = c.getActivities().stream()
        .filter(a -> a.getVisibility() == Visibility.CLAIMANT).toList();
    Set<UUID> actorIds = new HashSet<>();
    visible.forEach(a -> actorIds.add(a.getActorId()));
    Map<UUID, String> names = names(actorIds);
    List<TimelineEntry> timeline = visible.stream()
        .map(a -> new TimelineEntry(a.getType(), a.getMessage(), a.getFromStatus(), a.getToStatus(),
            a.getCreatedAt(), names.get(a.getActorId())))
        .toList();

    OpenQuestion open = c.getInfoRequests().stream().filter(InfoRequest::isOpen).findFirst()
        .map(r -> new OpenQuestion(r.getQuestion(), r.getAskedAt())).orElse(null);

    String policyNumber = policies.findById(c.getPolicyId()).map(Policy::getPolicyNumber).orElse(null);
    return new ClaimantClaimDetail(c.getId(), c.getClaimNumber(), c.getPolicyId(), policyNumber, c.getLine(),
        c.getClaimType(), c.getStatus(), c.getIncidentAtLocal(), c.getMarketZone(), c.getReportedAt(),
        c.isLate(), c.getLateReason(), c.getContactPhone(), c.getDescription(), c.getCurrency(),
        c.getEstimate(),
        c.getLossDetails() instanceof MotorLoss m ? MotorDetails.from(m) : null,
        c.getLossDetails() instanceof PropertyLoss p ? PropertyDetails.from(p) : null,
        open, decision(c), open != null, timeline, c.getVersion());
  }

  private static DecisionView decision(Claim c) {
    if (c.getStatus() == ClaimStatus.REJECTED) {
      return new DecisionView("REJECTED", null, null, c.getRejectionCode(), c.getRejectionExplanation(),
          c.getDecidedAt());
    }
    if (c.getApprovedAmount() != null) {
      return new DecisionView("APPROVED", c.getApprovedAmount(), c.getCurrency(), null, null, c.getDecidedAt());
    }
    return null;
  }

  /** An open information request exists exactly while the claim is AWAITING_INFO (LIFECYCLE.md). */
  ClaimantClaimSummary summary(Claim c) {
    return new ClaimantClaimSummary(c.getId(), c.getClaimNumber(), c.getLine(), c.getClaimType(), c.getStatus(),
        c.getIncidentAtLocal(), c.getReportedAt(), c.getCurrency(), c.getEstimate(), c.isLate(),
        c.getStatus() == ClaimStatus.AWAITING_INFO);
  }
}
