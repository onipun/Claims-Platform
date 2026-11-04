package com.chubb.claims.claim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Rules from docs/domain/LIFECYCLE.md that the first mutation run showed were not pinned down. */
class DomainRulesMutationTest {

  static final UUID HOLDER = AccessRulesTest.HOLDER;
  static final UUID OFFICER = AccessRulesTest.OFFICER;
  static final ClaimContext CTX = new ClaimContext(HOLDER, "SG", OFFICER);

  private static ClaimSnapshot snap(ClaimStatus status) {
    return new ClaimSnapshot(status, HOLDER, "SG", "SGD", new BigDecimal("50000"), OFFICER, new BigDecimal("900"));
  }

  private static Actor assigned() {
    return AccessRulesTest.officer(OFFICER, "SG");
  }

  private static void assertCode(Runnable r, ErrorCode code) {
    assertThatThrownBy(r::run).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  // ---- who may act: ANSWER_INFO is for the owning CLAIMANT only ----

  @Test
  void aStaffMemberWhoIsThePolicyholderCannotAnswerInfoAsTheClaimant() {
    Actor staffHolder = new Actor(HOLDER, Role.OFFICER, Set.of("SG"), new BigDecimal("1000"));
    assertThat(ActorPermissions.access(staffHolder, CTX, ClaimAction.ANSWER_INFO))
        .isInstanceOf(PermissionResult.NotFound.class);
    Actor claimant = new Actor(HOLDER, Role.CLAIMANT, Set.of("SG"), Map.of());
    assertThat(ActorPermissions.access(claimant, CTX, ClaimAction.ANSWER_INFO))
        .isInstanceOf(PermissionResult.Allowed.class);
  }

  // ---- ASSIGN target: an OFFICER or MANAGER of the claim's market, not the policyholder ----

  @Test
  void aQualifiedAssignTargetIsAllowed() {
    for (Role role : List.of(Role.OFFICER, Role.MANAGER)) {
      assertThat(ActorPermissions.preconditions(CTX, ClaimAction.ASSIGN,
              new TargetUser(UUID.randomUUID(), role, Set.of("SG", "HK"))))
          .isInstanceOf(PermissionResult.Allowed.class);
    }
  }

  @Test
  void aBadAssignTargetIsInvalidAssigneeAndThePolicyholderIsAConflict() {
    UUID other = UUID.randomUUID();
    assertThat(ActorPermissions.preconditions(CTX, ClaimAction.ASSIGN, null))
        .isEqualTo(new PermissionResult.Rejected(ErrorCode.INVALID_ASSIGNEE));
    assertThat(ActorPermissions.preconditions(CTX, ClaimAction.ASSIGN, new TargetUser(other, Role.CLAIMANT, Set.of("SG"))))
        .isEqualTo(new PermissionResult.Rejected(ErrorCode.INVALID_ASSIGNEE));
    assertThat(ActorPermissions.preconditions(CTX, ClaimAction.ASSIGN, new TargetUser(other, Role.OFFICER, Set.of("HK"))))
        .isEqualTo(new PermissionResult.Rejected(ErrorCode.INVALID_ASSIGNEE));
    assertThat(ActorPermissions.preconditions(CTX, ClaimAction.ASSIGN, new TargetUser(HOLDER, Role.OFFICER, Set.of("SG"))))
        .isEqualTo(new PermissionResult.Forbidden(ErrorCode.CONFLICT_OF_INTEREST));
    // only ASSIGN has preconditions: a null target on any other action is fine
    assertThat(ActorPermissions.preconditions(CTX, ClaimAction.ADD_NOTE, null))
        .isInstanceOf(PermissionResult.Allowed.class);
  }

  // ---- authority: a MANAGER limit of null is unlimited, a set limit binds ----

  @Test
  void aManagerWithALimitIsBoundByItAndOneWithoutIsNot() {
    BigDecimal limit = new BigDecimal("5000");
    AuthorityCheck.check(Role.MANAGER, limit, limit); // equal is within
    assertCode(() -> AuthorityCheck.check(Role.MANAGER, limit, new BigDecimal("5000.01")), ErrorCode.AUTHORITY_EXCEEDED);
    AuthorityCheck.check(Role.MANAGER, null, new BigDecimal("1000000000"));
  }

  @Test
  void noRowInTheMarketIsNoAuthorityForAnyRoleAndANullLimitRowIsUnlimitedForAManager() {
    BigDecimal any = new BigDecimal("1");
    Map<String, BigDecimal> nullLimitInSg = new java.util.HashMap<>();
    nullLimitInSg.put("SG", null);
    Actor manager = new Actor(UUID.randomUUID(), Role.MANAGER, Set.of("SG", "HK"), nullLimitInSg);

    assertThat(manager.hasRowIn("SG")).isTrue();
    assertThat(manager.hasRowIn("HK")).isFalse();
    AuthorityCheck.check(manager, "SG", new BigDecimal("1000000000"));
    // A manager removed from a market: the map's get returns null for HK, which alone would read as unlimited.
    assertCode(() -> AuthorityCheck.check(manager, "HK", any), ErrorCode.AUTHORITY_EXCEEDED);

    Actor officer = new Actor(UUID.randomUUID(), Role.OFFICER, Set.of("SG", "HK"), Map.of("SG", new BigDecimal("100")));
    AuthorityCheck.check(officer, "SG", any);
    assertCode(() -> AuthorityCheck.check(officer, "HK", any), ErrorCode.AUTHORITY_EXCEEDED);
  }

  @Test
  void theAuthorityLimitFollowsTheActorsMarketAndAMarketWithoutAnEntryHasNone() {
    Actor mgr = new Actor(UUID.randomUUID(), Role.MANAGER, Set.of("SG", "HK"),
        Map.of("SG", new BigDecimal("1000")));
    ClaimSnapshot s = snap(ClaimStatus.ASSESSED);
    assertCode(() -> ClaimDecider.decide(s, Command.approve(mgr, new BigDecimal("1001"))), ErrorCode.AUTHORITY_EXCEEDED);
    assertThat(ClaimDecider.decide(s, Command.approve(mgr, new BigDecimal("1000"))).status())
        .isEqualTo(ClaimStatus.APPROVED);
  }

  @Test
  void actorBuildsItsLimitsFromAMapOrFromOneLimitForEveryMarket() {
    Actor fromNullMap = new Actor(OFFICER, Role.OFFICER, Set.of("SG"), (Map<String, BigDecimal>) null);
    assertThat(fromNullMap.authorityLimit("SG")).isNull();
    Actor noLimit = new Actor(OFFICER, Role.OFFICER, Set.of("SG"), (BigDecimal) null);
    assertThat(noLimit.authorityLimit("SG")).isNull();
    Actor every = new Actor(OFFICER, Role.OFFICER, Set.of("SG", "HK"), new BigDecimal("750"));
    assertThat(every.authorityLimit("SG")).isEqualByComparingTo("750");
    assertThat(every.authorityLimit("HK")).isEqualByComparingTo("750");
    assertThat(every.authorityLimit("ID")).isNull();
    Map<String, BigDecimal> src = new java.util.HashMap<>(Map.of("SG", BigDecimal.ONE));
    Actor copy = new Actor(OFFICER, Role.OFFICER, Set.of("SG"), src);
    src.put("HK", BigDecimal.TEN);
    assertThat(copy.authorityLimit("HK")).isNull();
    assertThatThrownBy(() -> copy.authorityLimits().put("HK", BigDecimal.TEN))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ---- amount rules: a missing amount is INVALID_AMOUNT, never a crash ----

  @Test
  void aMissingAmountIsInvalidAmount() {
    BigDecimal limit = new BigDecimal("1000");
    assertCode(() -> AmountRules.submitEstimate(null, "SGD"), ErrorCode.INVALID_AMOUNT);
    assertCode(() -> AmountRules.assessReserve(null, limit, "SGD"), ErrorCode.INVALID_AMOUNT);
    assertCode(() -> AmountRules.approve(null, limit, "SGD"), ErrorCode.INVALID_AMOUNT);
    assertCode(() -> AmountRules.settle(null, limit, "SGD"), ErrorCode.INVALID_AMOUNT);
    assertCode(() -> AmountRules.settle(limit, null, "SGD"), ErrorCode.INVALID_AMOUNT);
  }

  @Test
  void amountBoundariesFollowTheLifecycleTable() {
    BigDecimal limit = new BigDecimal("1000");
    AmountRules.assessReserve(BigDecimal.ZERO, limit, "SGD");
    AmountRules.assessReserve(limit, limit, "SGD");
    assertCode(() -> AmountRules.assessReserve(new BigDecimal("1000.01"), limit, "SGD"), ErrorCode.INVALID_AMOUNT);
    assertCode(() -> AmountRules.assessReserve(new BigDecimal("-0.01"), limit, "SGD"), ErrorCode.INVALID_AMOUNT);
    AmountRules.approve(new BigDecimal("0.01"), limit, "SGD");
    AmountRules.approve(limit, limit, "SGD");
    assertCode(() -> AmountRules.approve(BigDecimal.ZERO, limit, "SGD"), ErrorCode.INVALID_AMOUNT);
    assertCode(() -> AmountRules.approve(new BigDecimal("1000.01"), limit, "SGD"), ErrorCode.INVALID_AMOUNT);
    AmountRules.settle(new BigDecimal("900.00"), new BigDecimal("900"), "SGD"); // equal by value
    assertCode(() -> AmountRules.settle(new BigDecimal("899.99"), new BigDecimal("900"), "SGD"), ErrorCode.INVALID_AMOUNT);
    assertCode(() -> AmountRules.settle(new BigDecimal("900.01"), new BigDecimal("900"), "SGD"), ErrorCode.INVALID_AMOUNT);
    // minor units: IDR has none, others two
    assertCode(() -> AmountRules.approve(new BigDecimal("10.5"), limit, "IDR"), ErrorCode.INVALID_AMOUNT);
    assertCode(() -> AmountRules.approve(new BigDecimal("10.555"), limit, "SGD"), ErrorCode.INVALID_AMOUNT);
    assertCode(() -> AmountRules.settle(new BigDecimal("900.5"), new BigDecimal("900.5"), "IDR"), ErrorCode.INVALID_AMOUNT);
  }

  // ---- required text and reject reason ----

  @Test
  void noteRequestAndAnswerNeedText() {
    Actor claimant = new Actor(HOLDER, Role.CLAIMANT, Set.of("SG"), Map.of());
    for (String bad : new String[] {null, "", "   "}) {
      assertCode(() -> ClaimDecider.decide(snap(ClaimStatus.UNDER_REVIEW), Command.note(assigned(), bad)),
          ErrorCode.VALIDATION_FAILED);
      assertCode(() -> ClaimDecider.decide(snap(ClaimStatus.UNDER_REVIEW), Command.requestInfo(assigned(), bad)),
          ErrorCode.VALIDATION_FAILED);
      assertCode(() -> ClaimDecider.decide(snap(ClaimStatus.AWAITING_INFO), Command.answerInfo(claimant, bad)),
          ErrorCode.VALIDATION_FAILED);
    }
    Decision note = ClaimDecider.decide(snap(ClaimStatus.UNDER_REVIEW), Command.note(assigned(), "called garage"));
    assertThat(note.status()).isEqualTo(ClaimStatus.UNDER_REVIEW);
    assertThat(note.message()).isEqualTo("called garage");
    assertThat(note.infoChange()).isEqualTo(Decision.InfoChange.NONE);
    assertThat(note.visibility()).isEqualTo(Decision.Visibility.INTERNAL);
    Decision ask = ClaimDecider.decide(snap(ClaimStatus.UNDER_REVIEW), Command.requestInfo(assigned(), "photos?"));
    assertThat(ask.status()).isEqualTo(ClaimStatus.AWAITING_INFO);
    assertThat(ask.infoChange()).isEqualTo(Decision.InfoChange.OPEN);
    assertThat(ask.visibility()).isEqualTo(Decision.Visibility.CLAIMANT);
    Decision ans = ClaimDecider.decide(snap(ClaimStatus.AWAITING_INFO), Command.answerInfo(claimant, "attached"));
    assertThat(ans.status()).isEqualTo(ClaimStatus.UNDER_REVIEW);
    assertThat(ans.infoChange()).isEqualTo(Decision.InfoChange.ANSWER);
  }

  @Test
  void rejectNeedsAReasonAndAClaimantVisibleExplanation() {
    ClaimSnapshot s = snap(ClaimStatus.UNDER_REVIEW);
    assertCode(() -> ClaimDecider.decide(s, Command.reject(assigned(), null, "not covered")), ErrorCode.VALIDATION_FAILED);
    assertCode(() -> ClaimDecider.decide(s, Command.reject(assigned(), RejectReason.NOT_COVERED, null)),
        ErrorCode.VALIDATION_FAILED);
    assertCode(() -> ClaimDecider.decide(s, Command.reject(assigned(), RejectReason.NOT_COVERED, "  ")),
        ErrorCode.VALIDATION_FAILED);
    Decision d = ClaimDecider.decide(s, Command.reject(assigned(), RejectReason.EXCLUDED_CAUSE, "flood is excluded"));
    assertThat(d.status()).isEqualTo(ClaimStatus.REJECTED);
    assertThat(d.rejectReason()).isEqualTo(RejectReason.EXCLUDED_CAUSE);
    assertThat(d.rejectionExplanation()).isEqualTo("flood is excluded");
    assertThat(d.ledgerEntry().type()).isEqualTo(LedgerType.RESERVE_SET);
    assertThat(d.ledgerEntry().amount()).isEqualByComparingTo("0");
    assertThat(d.reserveStatus()).isEqualTo(ReserveStatus.ASSESSED);
  }

  // ---- submission: incident time and loss details ----

  private static final ZoneId SG = ZoneId.of("Asia/Singapore");

  private static PolicyFacts policy(Line line, LocalDate from, LocalDate to) {
    return new PolicyFacts(HOLDER, line, "SGD", SG, from, to);
  }

  @Test
  void anIncidentTimeIsRequired() {
    SubmissionInput in = new SubmissionInput(HOLDER, Line.MOTOR, ClaimType.OWN_DAMAGE, null, new BigDecimal("10"),
        AccessRulesTest.GOOD_MOTOR, "+65", "x");
    List<Violation> v = SubmissionValidator.validate(in,
        policy(Line.MOTOR, LocalDate.of(2025, 1, 1), LocalDate.of(2030, 12, 31)), AccessRulesTest.NOW);
    assertThat(v).containsExactly(new Violation(ErrorCode.VALIDATION_FAILED, "incidentAtLocal", "Incident time is required"));
  }

  @Test
  void anIncidentAfterThePolicyEndedIsOutsideThePeriodInclusiveOfTheLastDay() {
    PolicyFacts ended = policy(Line.MOTOR, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 11, 2));
    assertThat(AccessRulesTest.codes(AccessRulesTest.motor(LocalDateTime.parse("2026-11-03T00:00:00"),
            ClaimType.OWN_DAMAGE, AccessRulesTest.GOOD_MOTOR), ended))
        .containsExactly(ErrorCode.INCIDENT_OUTSIDE_POLICY_PERIOD);
    assertThat(AccessRulesTest.codes(AccessRulesTest.motor(LocalDateTime.parse("2026-11-02T23:59:00"),
            ClaimType.OWN_DAMAGE, AccessRulesTest.GOOD_MOTOR), ended))
        .isEmpty();
  }

  @Test
  void aPropertyClaimNeedsPropertyLossDetails() {
    PolicyFacts p = policy(Line.PROPERTY, LocalDate.of(2025, 1, 1), LocalDate.of(2030, 12, 31));
    LocalDateTime t = LocalDateTime.parse("2026-11-03T09:00:00");
    for (LossDetails bad : new LossDetails[] {null, AccessRulesTest.GOOD_MOTOR}) {
      SubmissionInput in = new SubmissionInput(HOLDER, Line.PROPERTY, ClaimType.FIRE, t, new BigDecimal("5000"), bad, "+65", "x");
      assertThat(SubmissionValidator.validate(in, p, AccessRulesTest.NOW))
          .containsExactly(new Violation(ErrorCode.VALIDATION_FAILED, "lossDetails", "Property loss details are required"));
    }
  }

  @Test
  void blankPropertyFieldsAreReportedPerField() {
    PolicyFacts p = policy(Line.PROPERTY, LocalDate.of(2025, 1, 1), LocalDate.of(2030, 12, 31));
    SubmissionInput in = new SubmissionInput(HOLDER, Line.PROPERTY, ClaimType.FIRE,
        LocalDateTime.parse("2026-11-03T09:00:00"), new BigDecimal("5000"), new PropertyLoss(" ", null, null), "+65", "x");
    assertThat(SubmissionValidator.validate(in, p, AccessRulesTest.NOW)).extracting(Violation::field)
        .containsExactly("lossDetails.address", "lossDetails.cause");
  }

  @Test
  void aClaimTypeNotInTheLineIsNotAllowedAndNullIsNever() {
    assertThat(Line.MOTOR.allows(ClaimType.FIRE)).isFalse();
    assertThat(Line.PROPERTY.allows(ClaimType.WINDSCREEN)).isFalse();
    assertThat(Line.MOTOR.allows(ClaimType.THEFT)).isTrue();
    assertThat(Line.PROPERTY.allows(ClaimType.THEFT)).isTrue();
    assertThat(Line.MOTOR.allows(null)).isFalse();
  }

  // ---- error codes carry their HTTP status (LIFECYCLE.md) ----

  @Test
  void errorCodesMapToTheDocumentedStatus() {
    assertThat(ErrorCode.NOT_FOUND.httpStatus()).isEqualTo(404);
    assertThat(ErrorCode.CONFLICT_OF_INTEREST.httpStatus()).isEqualTo(403);
    assertThat(ErrorCode.AUTHORITY_EXCEEDED.httpStatus()).isEqualTo(403);
    assertThat(ErrorCode.ILLEGAL_TRANSITION.httpStatus()).isEqualTo(409);
    assertThat(ErrorCode.INVALID_AMOUNT.httpStatus()).isEqualTo(422);
    assertThat(ErrorCode.INVALID_ASSIGNEE.httpStatus()).isEqualTo(422);
    assertThat(ErrorCode.VALIDATION_FAILED.httpStatus()).isEqualTo(422);
    assertThat(ErrorCode.POLICY_MISMATCH.httpStatus()).isEqualTo(422);
    assertThat(ErrorCode.INVALID_CLAIM_TYPE.httpStatus()).isEqualTo(422);
    assertThat(ErrorCode.INCIDENT_IN_FUTURE.httpStatus()).isEqualTo(422);
    assertThat(ErrorCode.INCIDENT_OUTSIDE_POLICY_PERIOD.httpStatus()).isEqualTo(422);
  }

  @Test
  void aRejectedSubmissionCarriesEveryViolation() {
    Violation a = new Violation(ErrorCode.VALIDATION_FAILED, "a", "m");
    Violation b = new Violation(ErrorCode.INVALID_AMOUNT, "b", "n");
    assertThat(new SubmissionRejectedException(List.of(a, b)).violations()).containsExactly(a, b);
  }
}
