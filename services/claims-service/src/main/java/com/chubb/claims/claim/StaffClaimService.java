package com.chubb.claims.claim;

import com.chubb.claims.claim.api.PageResponse;
import com.chubb.claims.claim.api.StaffClaimDetail;
import com.chubb.claims.claim.api.StaffClaimSummary;
import com.chubb.claims.claim.api.StaffFinancials;
import com.chubb.claims.claim.api.StaffOfficer;
import com.chubb.claims.claim.domain.Actor;
import com.chubb.claims.claim.domain.ClaimStatus;
import com.chubb.claims.claim.domain.Command;
import com.chubb.claims.claim.domain.DomainException;
import com.chubb.claims.claim.domain.ErrorCode;
import com.chubb.claims.claim.domain.RejectReason;
import com.chubb.claims.claim.domain.Role;
import com.chubb.claims.claim.domain.TargetUser;
import com.chubb.claims.identity.AuthenticatedUser;
import com.chubb.claims.reference.AppUser;
import com.chubb.claims.reference.AppUserRepository;
import com.chubb.claims.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff reads and commands. Every lookup is scoped to the token's markets (T10, trap 6). Command
 * order (LIFECYCLE.md): market scope (404), access incl. conflict of interest (404/403), If-Match
 * present (428) and current (409), then ClaimDecider (409 transition, preconditions, 422, 403 authority).
 */
@Service
@Transactional
public class StaffClaimService {
  static final int MAX_PAGE_SIZE = 100;
  /** page * size is an int offset in the query; an unbounded page overflowed it into a 500. */
  static final int MAX_PAGE = 1_000_000;

  public enum Queue { UNASSIGNED, MINE, ALL }

  private final ClaimRepository claims;
  private final AppUserRepository users;
  private final StaffClaimMapper mapper;
  private final ClaimEventRecorder recorder;
  private final Clock clock;

  StaffClaimService(ClaimRepository claims, AppUserRepository users, StaffClaimMapper mapper,
      ClaimEventRecorder recorder, Clock clock) {
    this.claims = claims;
    this.users = users;
    this.mapper = mapper;
    this.recorder = recorder;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResponse<StaffClaimSummary> list(AuthenticatedUser user, Queue queue, ClaimStatus status, int page,
      int size) {
    Specification<Claim> spec = (root, q, cb) -> root.get("marketCode").in(user.markets());
    // Nobody sees their own claims in the staff lists: detail answers 403 CONFLICT_OF_INTEREST for them.
    spec = spec.and((root, q, cb) -> cb.notEqual(root.get("claimantId"), user.id()));
    if (status != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
    Sort sort = switch (queue) {
      case UNASSIGNED -> Sort.by(Sort.Order.asc("pickupDueAt"), Sort.Order.asc("claimNumber"));
      case MINE, ALL -> Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.asc("claimNumber"));
    };
    switch (queue) {
      case UNASSIGNED -> spec = spec.and((root, q, cb) -> cb.isNull(root.get("assignedOfficerId")));
      case MINE -> spec = spec.and((root, q, cb) -> cb.equal(root.get("assignedOfficerId"), user.id()));
      case ALL -> { }
    }
    Page<Claim> p = claims.findAll(spec, PageRequest.of(Math.min(Math.max(page, 0), MAX_PAGE), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), sort));
    List<UUID> officerIds = new ArrayList<>();
    p.forEach(c -> officerIds.add(c.getAssignedOfficerId()));
    Map<UUID, String> names = mapper.names(officerIds);
    return PageResponse.of(p.map(c -> mapper.summary(c, names)));
  }

  @Transactional(readOnly = true)
  public StaffClaimDetail get(AuthenticatedUser user, UUID claimId) {
    return mapper.detail(readable(user, claimId), actor(user));
  }

  @Transactional(readOnly = true)
  public StaffFinancials financials(AuthenticatedUser user, UUID claimId) {
    return mapper.financials(readable(user, claimId));
  }

  @Transactional(readOnly = true)
  public List<StaffOfficer> officers(AuthenticatedUser user) {
    return users.findStaffInMarkets(List.of(Role.OFFICER, Role.MANAGER), user.markets()).stream()
        .map(u -> new StaffOfficer(u.getId(), u.getName(), u.getRole(),
            u.getMarkets().stream().filter(user.markets()::contains).sorted().toList()))
        .toList();
  }

  // ---- commands ----

  public StaffClaimDetail pickUp(AuthenticatedUser u, UUID id, String ifMatch) {
    return run(u, id, ifMatch, "CLAIM_ASSIGNED", Command::pickUp);
  }

  public StaffClaimDetail assign(AuthenticatedUser u, UUID id, String ifMatch, UUID officerId) {
    return run(u, id, ifMatch, "CLAIM_ASSIGNED", a -> Command.assign(a, target(officerId)));
  }

  public StaffClaimDetail note(AuthenticatedUser u, UUID id, String ifMatch, String text) {
    return run(u, id, ifMatch, "NOTE_ADDED", a -> Command.note(a, text));
  }

  public StaffClaimDetail requestInfo(AuthenticatedUser u, UUID id, String ifMatch, String question) {
    return run(u, id, ifMatch, "INFO_REQUESTED", a -> Command.requestInfo(a, question));
  }

  public StaffClaimDetail assess(AuthenticatedUser u, UUID id, String ifMatch, BigDecimal reserve) {
    return run(u, id, ifMatch, "CLAIM_ASSESSED", a -> Command.assess(a, reserve));
  }

  public StaffClaimDetail approve(AuthenticatedUser u, UUID id, String ifMatch, BigDecimal amount) {
    return run(u, id, ifMatch, "CLAIM_APPROVED", a -> Command.approve(a, amount));
  }

  public StaffClaimDetail reject(AuthenticatedUser u, UUID id, String ifMatch, RejectReason reason, String text) {
    return run(u, id, ifMatch, "CLAIM_REJECTED", a -> Command.reject(a, reason, text));
  }

  public StaffClaimDetail settle(AuthenticatedUser u, UUID id, String ifMatch, BigDecimal amount) {
    return run(u, id, ifMatch, "CLAIM_SETTLED", a -> Command.settle(a, amount));
  }

  /** The saveAndFlush makes the version final before the recorder runs (trap 1). */
  StaffClaimDetail run(AuthenticatedUser user, UUID claimId, String ifMatch, String eventType,
      Function<Actor, Command> command) {
    Claim claim = scoped(user, claimId);
    Actor actor = actor(user);
    Command cmd = command.apply(actor);
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
    recorder.record(claim, eventType);
    return mapper.detail(claim, actor);
  }

  private TargetUser target(UUID officerId) {
    return users.findById(officerId).map(u -> new TargetUser(u.getId(), u.getRole(), u.getMarkets())).orElse(null);
  }

  /** Authority limits are not in the token (AuthenticatedUser), so staff load them. */
  private Actor actor(AuthenticatedUser user) {
    AppUser u = users.findById(user.id()).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Not found"));
    // One key per user_market row, read fresh from the database (not the token): a null value is a row
    // with no limit. Hibernate drops null map values, so the key set comes from the markets collection.
    Map<String, BigDecimal> limits = new java.util.HashMap<>();
    for (String m : u.getMarkets()) limits.put(m, u.getAuthorityLimits().get(m));
    return new Actor(user.id(), user.role(), user.markets(), limits);
  }

  /** Staff reads apply the same conflict-of-interest rule as commands: nobody reads their own claim as staff. */
  private Claim readable(AuthenticatedUser user, UUID claimId) {
    Claim claim = scoped(user, claimId);
    if (user.id().equals(claim.getClaimantId())) {
      throw new DomainException(ErrorCode.CONFLICT_OF_INTEREST, "CONFLICT_OF_INTEREST");
    }
    return claim;
  }

  private Claim scoped(AuthenticatedUser user, UUID claimId) {
    return claims.findByIdAndMarketCodeIn(claimId, user.markets())
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Claim not found"));
  }
}
