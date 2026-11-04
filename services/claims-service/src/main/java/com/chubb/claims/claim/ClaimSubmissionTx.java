package com.chubb.claims.claim;

import com.chubb.claims.claim.api.ClaimantClaimDetail;
import com.chubb.claims.claim.api.SubmitClaimRequest;
import com.chubb.claims.claim.domain.DomainException;
import com.chubb.claims.claim.domain.ErrorCode;
import com.chubb.claims.claim.domain.LateReportRule;
import com.chubb.claims.claim.domain.Line;
import com.chubb.claims.claim.domain.LossDetails;
import com.chubb.claims.claim.domain.PolicyFacts;
import com.chubb.claims.claim.domain.SubmissionInput;
import com.chubb.claims.claim.domain.SubmissionRejectedException;
import com.chubb.claims.claim.domain.SubmissionValidator;
import com.chubb.claims.claim.domain.Violation;
import com.chubb.claims.identity.AuthenticatedUser;
import com.chubb.claims.reference.Market;
import com.chubb.claims.reference.MarketRepository;
import com.chubb.claims.reference.MarketRuleRepository;
import com.chubb.claims.reference.Policy;
import com.chubb.claims.reference.PolicyRepository;
import com.chubb.claims.web.ApiException;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional steps of a submission. {@link ClaimSubmissionService} calls these from outside
 * any transaction, so a unique-constraint failure (which poisons the transaction it happens in) is
 * handled in a new one.
 */
@Service
public class ClaimSubmissionTx {
  static final String NEXT_WORKING_DAY = "NEXT_WORKING_DAY";

  private final ClaimRepository claims;
  private final PolicyRepository policies;
  private final MarketRepository markets;
  private final MarketRuleRepository marketRules;
  private final ClaimantClaimMapper mapper;
  private final ClaimEventRecorder recorder;
  private final EntityManager em;
  private final Clock clock;
  private final SlaProperties sla;

  ClaimSubmissionTx(ClaimRepository claims, PolicyRepository policies, MarketRepository markets,
      MarketRuleRepository marketRules, ClaimantClaimMapper mapper, ClaimEventRecorder recorder,
      EntityManager em, Clock clock, SlaProperties sla) {
    this.claims = claims;
    this.policies = policies;
    this.markets = markets;
    this.marketRules = marketRules;
    this.mapper = mapper;
    this.recorder = recorder;
    this.em = em;
    this.clock = clock;
    this.sla = sla;
  }

  @Transactional(readOnly = true)
  public Optional<ClaimantClaimDetail> findExisting(UUID claimantId, String idempotencyKey, String requestHash) {
    return claims.findByClaimantIdAndIdempotencyKey(claimantId, idempotencyKey).map(existing -> {
      if (existing.getRequestHash() != null && !existing.getRequestHash().equals(requestHash)) {
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
            "This Idempotency-Key was already used with a different request");
      }
      return mapper.detail(existing);
    });
  }

  @Transactional
  public ClaimantClaimDetail create(AuthenticatedUser user, String idempotencyKey, String requestHash,
      SubmitClaimRequest req) {
    // An unknown policy and someone else's policy answer identically (T8).
    Policy policy = policies.findById(req.policyId())
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Policy not found"));
    Market market = markets.findById(policy.getMarketCode()).orElseThrow();
    ZoneId zone = market.zone();

    LossDetails loss = req.line() == Line.MOTOR
        ? (req.motor() == null ? null : req.motor().toDomain())
        : (req.property() == null ? null : req.property().toDomain());
    SubmissionInput input = new SubmissionInput(user.id(), req.line(), req.claimType(),
        req.incidentAtLocal(), req.estimate(), loss, req.contactPhone(), req.description());
    PolicyFacts facts = new PolicyFacts(policy.getHolderId(), policy.getLine(), market.getCurrency(), zone,
        policy.getEffectiveFrom(), policy.getEffectiveTo());

    List<Violation> violations = SubmissionValidator.validate(input, facts, clock);
    if (violations.stream().anyMatch(v -> v.code() == ErrorCode.NOT_FOUND)) {
      throw new DomainException(ErrorCode.NOT_FOUND, "Policy not found");
    }
    if (!violations.isEmpty()) throw new SubmissionRejectedException(violations);

    Instant now = clock.instant();
    // The flag exists only where a sourced market_rule row says so (D16); never rejects.
    boolean hasRule = marketRules.existsByMarketCodeAndLineAndRuleType(
        market.getCode(), policy.getLine().name(), NEXT_WORKING_DAY);
    boolean late = hasRule
        && new LateReportRule(clock).evaluate(market.getCode(), policy.getLine(), zone, req.incidentAtLocal()).late();
    String lateReason = late && req.lateReason() != null && !req.lateReason().isBlank()
        ? req.lateReason().trim() : null;

    int year = now.atZone(zone).getYear();
    String claimNumber = (String) em.createNativeQuery("select next_claim_number(?1, ?2)")
        .setParameter(1, market.getCode()).setParameter(2, year).getSingleResult();

    Claim claim = Claim.submit(new Claim.NewClaim(claimNumber, policy.getId(), user.id(), market.getCode(),
        policy.getLine(), req.claimType(), req.incidentAtLocal(), zone.getId(), now, late, lateReason,
        req.contactPhone(), req.description(), loss, req.estimate(), policy.getCoverageLimit(),
        policy.getDeductible(), market.getCurrency(), now.plus(sla.pickupHours(), ChronoUnit.HOURS),
        now.plus(sla.decisionDays(), ChronoUnit.DAYS), idempotencyKey, requestHash), now);

    // Flush here so a duplicate key fails inside this method and the caller can recover.
    claims.saveAndFlush(claim);
    recorder.record(claim, "CLAIM_SUBMITTED");
    return mapper.detail(claim);
  }
}
