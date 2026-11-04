package com.chubb.claims.claim;

import com.chubb.claims.claim.api.ClaimantClaimDetail;
import com.chubb.claims.claim.api.ClaimantClaimSummary;
import com.chubb.claims.claim.api.ClaimantPolicy;
import com.chubb.claims.claim.api.ClaimantUpdate;
import com.chubb.claims.claim.api.PageResponse;
import com.chubb.claims.claim.domain.Command;
import com.chubb.claims.claim.domain.Decision.Visibility;
import com.chubb.claims.claim.domain.DomainException;
import com.chubb.claims.claim.domain.ErrorCode;
import com.chubb.claims.identity.AuthenticatedUser;
import com.chubb.claims.reference.MarketRepository;
import com.chubb.claims.reference.PolicyRepository;
import com.chubb.claims.web.ApiException;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Claimant-side reads and the one claimant command. Every lookup is scoped to the caller. */
@Service
public class ClaimantClaimService {
  static final int MAX_PAGE_SIZE = 100;
  static final int MAX_PAGE = 1_000_000;

  private final ClaimRepository claims;
  private final ClaimActivityRepository activities;
  private final PolicyRepository policies;
  private final MarketRepository markets;
  private final ClaimantClaimMapper mapper;
  private final ClaimEventRecorder recorder;
  private final Clock clock;

  ClaimantClaimService(ClaimRepository claims, ClaimActivityRepository activities, PolicyRepository policies,
      MarketRepository markets, ClaimantClaimMapper mapper, ClaimEventRecorder recorder, Clock clock) {
    this.claims = claims;
    this.activities = activities;
    this.policies = policies;
    this.markets = markets;
    this.mapper = mapper;
    this.recorder = recorder;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<ClaimantPolicy> policies(AuthenticatedUser user) {
    // One query for the markets, not one per policy.
    Map<String, String> currency = new java.util.HashMap<>();
    markets.findAll().forEach(m -> currency.put(m.getCode(), m.getCurrency()));
    return policies.findByHolderIdOrderByPolicyNumber(user.id()).stream()
        .map(p -> new ClaimantPolicy(p.getId(), p.getPolicyNumber(), p.getLine(), p.getMarketCode(),
            currency.get(p.getMarketCode()),
            p.getCoverageLimit(), p.getDeductible(), p.getEffectiveFrom(), p.getEffectiveTo()))
        .toList();
  }

  @Transactional(readOnly = true)
  public PageResponse<ClaimantClaimSummary> list(AuthenticatedUser user, int page, int size) {
    Page<Claim> p = claims.findByClaimantId(user.id(), PageRequest.of(page(page), size(size),
        Sort.by(Sort.Order.desc("reportedAt"), Sort.Order.desc("claimNumber"))));
    return PageResponse.of(p.map(mapper::summary));
  }

  @Transactional(readOnly = true)
  public ClaimantClaimDetail get(AuthenticatedUser user, UUID claimId) {
    return mapper.detail(owned(user, claimId));
  }

  @Transactional(readOnly = true)
  public PageResponse<ClaimantUpdate> updates(AuthenticatedUser user, int page, int size) {
    Page<ClaimActivityRepository.UpdateRow> rows = activities.findVisibleUpdates(
        user.id(), Visibility.CLAIMANT, PageRequest.of(page(page), size(size)));
    Map<UUID, String> names = mapper.names(new HashSet<>(rows.getContent().stream()
        .map(ClaimActivityRepository.UpdateRow::actorId).toList()));
    return PageResponse.of(rows.map(r -> new ClaimantUpdate(r.id(), r.claimId(), r.claimNumber(), r.type(),
        r.message(), r.fromStatus(), r.toStatus(), r.createdAt(), names.get(r.actorId()))));
  }

  /** Order: ownership (404), access (404), If-Match present (428) and current (409), then the domain rules (409). */
  @Transactional
  public ClaimantClaimDetail answerInfo(AuthenticatedUser user, UUID claimId, String ifMatch, String answer) {
    Claim claim = owned(user, claimId);
    Command cmd = Command.answerInfo(user.toActor(), answer);
    claim.checkAccess(cmd);
    if (ifMatch == null || ifMatch.isBlank()) {
      throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED",
          "If-Match header with the claim's ETag is required");
    }
    if (ETags.parseVersion(ifMatch) != claim.getVersion()) {
      throw new ApiException(HttpStatus.CONFLICT, "STALE_VERSION",
          "The claim was changed by someone else. Reload and try again");
    }
    claim.execute(cmd, clock.instant());
    claims.saveAndFlush(claim);
    recorder.record(claim, "INFO_ANSWERED");
    return mapper.detail(claim);
  }

  private Claim owned(AuthenticatedUser user, UUID claimId) {
    return claims.findByIdAndClaimantId(claimId, user.id())
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Claim not found"));
  }

  /** page * size is an int offset in the query: an unbounded page overflowed it into a 500. 1,000,000 pages of 100 is far past any data. */
  private static int page(int page) { return Math.min(Math.max(page, 0), MAX_PAGE); }

  private static int size(int size) { return Math.min(Math.max(size, 1), MAX_PAGE_SIZE); }
}
