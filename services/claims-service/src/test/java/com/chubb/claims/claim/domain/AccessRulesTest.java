package com.chubb.claims.claim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessRulesTest {

  static final UUID HOLDER = UUID.randomUUID();
  static final UUID OFFICER = UUID.randomUUID();
  static final UUID OTHER_OFFICER = UUID.randomUUID();
  static final UUID MANAGER = UUID.randomUUID();

  static Actor officer(UUID id, String... markets) {
    return new Actor(id, Role.OFFICER, Set.of(markets), new BigDecimal("10000"));
  }

  static Actor manager(String... markets) {
    return new Actor(MANAGER, Role.MANAGER, Set.of(markets), (BigDecimal) null);
  }

  static ClaimContext ctx(UUID assigned) {
    return new ClaimContext(HOLDER, "SG", assigned);
  }

  // ---- authority ----

  @Test
  void authorityCheck() {
    AuthorityCheck.check(Role.OFFICER, new BigDecimal("10000"), new BigDecimal("10000"));
    assertThatThrownBy(
            () -> AuthorityCheck.check(Role.OFFICER, new BigDecimal("10000"), new BigDecimal("10000.01")))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.AUTHORITY_EXCEEDED));
    AuthorityCheck.check(Role.MANAGER, null, new BigDecimal("99999999")); // unlimited
  }

  @Test
  void officerWithoutALimitHasNoAuthority() {
    assertThatThrownBy(() -> AuthorityCheck.check(Role.OFFICER, null, new BigDecimal("1")))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.AUTHORITY_EXCEEDED));
  }

  // ---- conflict of interest ----

  @Test
  void conflictCheck() {
    assertThatThrownBy(() -> ConflictCheck.check(HOLDER, HOLDER))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT_OF_INTEREST));
    ConflictCheck.check(OFFICER, HOLDER);
  }

  // ---- actor permissions ----

  @Test
  void pickUpNeedsStaffOfTheMarketAndNoOfficer() {
    assertThat(ActorPermissions.check(officer(OFFICER, "SG"), ctx(null), ClaimAction.PICK_UP, null))
        .isInstanceOf(PermissionResult.Allowed.class);
    assertThat(ActorPermissions.check(manager("SG"), ctx(null), ClaimAction.PICK_UP, null))
        .isInstanceOf(PermissionResult.Allowed.class);
    assertThat(ActorPermissions.check(officer(OFFICER, "HK"), ctx(null), ClaimAction.PICK_UP, null))
        .isInstanceOf(PermissionResult.NotFound.class);
    assertThat(
            ActorPermissions.check(
                new Actor(HOLDER, Role.CLAIMANT, Set.of("SG"), Map.of()), ctx(null), ClaimAction.PICK_UP, null))
        .isInstanceOf(PermissionResult.NotFound.class);
    // An officer already on the claim is not an access question: the transition table answers (409).
    assertThat(ActorPermissions.check(officer(OFFICER, "SG"), ctx(OTHER_OFFICER), ClaimAction.PICK_UP, null))
        .isInstanceOf(PermissionResult.Allowed.class);
  }

  @Test
  void assignIsManagerOnlyAndTargetMustQualify() {
    TargetUser ok = new TargetUser(OFFICER, Role.OFFICER, Set.of("SG"));
    assertThat(ActorPermissions.check(manager("SG"), ctx(null), ClaimAction.ASSIGN, ok))
        .isInstanceOf(PermissionResult.Allowed.class);
    // an officer cannot reassign, and sees nothing wrong with the id: 404
    assertThat(ActorPermissions.check(officer(OFFICER, "SG"), ctx(OFFICER), ClaimAction.ASSIGN, ok))
        .isInstanceOf(PermissionResult.NotFound.class);
    assertThat(
            ActorPermissions.check(
                manager("SG"), ctx(null), ClaimAction.ASSIGN,
                new TargetUser(OFFICER, Role.OFFICER, Set.of("HK"))))
        .isEqualTo(new PermissionResult.Rejected(ErrorCode.INVALID_ASSIGNEE));
    assertThat(
            ActorPermissions.check(
                manager("SG"), ctx(null), ClaimAction.ASSIGN,
                new TargetUser(OFFICER, Role.CLAIMANT, Set.of("SG"))))
        .isEqualTo(new PermissionResult.Rejected(ErrorCode.INVALID_ASSIGNEE));
    assertThat(
            ActorPermissions.check(
                manager("SG"), ctx(null), ClaimAction.ASSIGN,
                new TargetUser(HOLDER, Role.OFFICER, Set.of("SG"))))
        .isEqualTo(new PermissionResult.Forbidden(ErrorCode.CONFLICT_OF_INTEREST));
  }

  @Test
  void workActionsNeedTheAssignedOfficerOrAManagerOfTheMarket() {
    for (ClaimAction a :
        new ClaimAction[] {
          ClaimAction.ADD_NOTE, ClaimAction.REQUEST_INFO, ClaimAction.ASSESS, ClaimAction.REJECT,
          ClaimAction.SETTLE, ClaimAction.APPROVE
        }) {
      assertThat(ActorPermissions.check(officer(OFFICER, "SG"), ctx(OFFICER), a, null))
          .as("assigned officer %s", a).isInstanceOf(PermissionResult.Allowed.class);
      assertThat(ActorPermissions.check(manager("SG"), ctx(OFFICER), a, null))
          .as("manager %s", a).isInstanceOf(PermissionResult.Allowed.class);
      assertThat(ActorPermissions.check(officer(OTHER_OFFICER, "SG"), ctx(OFFICER), a, null))
          .as("unassigned officer %s", a).isInstanceOf(PermissionResult.NotFound.class);
      assertThat(ActorPermissions.check(manager("HK"), ctx(OFFICER), a, null))
          .as("other-market manager %s", a).isInstanceOf(PermissionResult.NotFound.class);
    }
  }

  @Test
  void conflictOfInterestComesAfterAccessChecks() {
    // The policyholder is also an assigned officer: access passes, conflict fires.
    Actor holderAsOfficer = officer(HOLDER, "SG");
    assertThat(ActorPermissions.check(holderAsOfficer, ctx(HOLDER), ClaimAction.ASSESS, null))
        .isEqualTo(new PermissionResult.Forbidden(ErrorCode.CONFLICT_OF_INTEREST));
    // If they have no access at all, they get 404, not a hint.
    assertThat(ActorPermissions.check(officer(HOLDER, "HK"), ctx(OFFICER), ClaimAction.ASSESS, null))
        .isInstanceOf(PermissionResult.NotFound.class);
    assertThat(ActorPermissions.check(holderAsOfficer, ctx(null), ClaimAction.PICK_UP, null))
        .isEqualTo(new PermissionResult.Forbidden(ErrorCode.CONFLICT_OF_INTEREST));
  }

  @Test
  void answerInfoIsForTheOwningClaimantOnly() {
    Actor owner = new Actor(HOLDER, Role.CLAIMANT, Set.of("SG"), Map.of());
    Actor stranger = new Actor(UUID.randomUUID(), Role.CLAIMANT, Set.of("SG"), Map.of());
    assertThat(ActorPermissions.check(owner, ctx(OFFICER), ClaimAction.ANSWER_INFO, null))
        .isInstanceOf(PermissionResult.Allowed.class);
    assertThat(ActorPermissions.check(stranger, ctx(OFFICER), ClaimAction.ANSWER_INFO, null))
        .isInstanceOf(PermissionResult.NotFound.class);
    assertThat(ActorPermissions.check(officer(OFFICER, "SG"), ctx(OFFICER), ClaimAction.ANSWER_INFO, null))
        .isInstanceOf(PermissionResult.NotFound.class);
  }

  // ---- order of checks (LIFECYCLE.md): access, transition table, preconditions, amount, authority ----

  private static ClaimSnapshot snap(ClaimStatus status, UUID assigned) {
    return new ClaimSnapshot(status, HOLDER, "SG", "SGD", new BigDecimal("50000"), assigned, null);
  }

  private static ErrorCode codeOf(ClaimSnapshot s, Command c) {
    return org.assertj.core.api.Assertions.catchThrowableOfType(DomainException.class, () -> ClaimDecider.decide(s, c))
        .code();
  }

  @Test
  void aBlankCellAlwaysGivesIllegalTransition() {
    // PICK_UP on a claim that is already assigned and under review
    assertThat(codeOf(snap(ClaimStatus.UNDER_REVIEW, OTHER_OFFICER), Command.pickUp(officer(OFFICER, "SG"))))
        .isEqualTo(ErrorCode.ILLEGAL_TRANSITION);
    // ANSWER_INFO by the owner when nothing was asked
    assertThat(codeOf(snap(ClaimStatus.UNDER_REVIEW, OFFICER),
            Command.answerInfo(new Actor(HOLDER, Role.CLAIMANT, Set.of("SG"), Map.of()), "x")))
        .isEqualTo(ErrorCode.ILLEGAL_TRANSITION);
    // ASSIGN with a bad target on a settled claim: the table wins over the target check
    assertThat(codeOf(snap(ClaimStatus.SETTLED, OFFICER),
            Command.assign(manager("SG"), new TargetUser(OFFICER, Role.CLAIMANT, Set.of("HK")))))
        .isEqualTo(ErrorCode.ILLEGAL_TRANSITION);
    // the amount is not looked at either: a negative amount on a claim that cannot be approved
    assertThat(codeOf(snap(ClaimStatus.UNDER_REVIEW, OFFICER), Command.approve(manager("SG"), new BigDecimal("-1"))))
        .isEqualTo(ErrorCode.ILLEGAL_TRANSITION);
  }

  @Test
  void accessComesBeforeTheTransitionTable() {
    // An unassigned officer of the market cannot see the claim, even though the action is illegal here
    assertThat(codeOf(snap(ClaimStatus.SETTLED, OFFICER), Command.note(officer(OTHER_OFFICER, "SG"), "x")))
        .isEqualTo(ErrorCode.NOT_FOUND);
    assertThat(codeOf(snap(ClaimStatus.SETTLED, OFFICER), Command.note(officer(HOLDER, "SG"), "x")))
        .as("the assigned-officer rule hides it first").isEqualTo(ErrorCode.NOT_FOUND);
    assertThat(codeOf(snap(ClaimStatus.SETTLED, null), Command.pickUp(officer(HOLDER, "SG"))))
        .isEqualTo(ErrorCode.CONFLICT_OF_INTEREST);
  }

  @Test
  void preconditionsComeAfterTheTableAndBeforeTheAmount() {
    assertThat(codeOf(snap(ClaimStatus.UNDER_REVIEW, OFFICER),
            Command.assign(manager("SG"), new TargetUser(OFFICER, Role.OFFICER, Set.of("HK")))))
        .isEqualTo(ErrorCode.INVALID_ASSIGNEE);
    assertThat(codeOf(snap(ClaimStatus.ASSESSED, OFFICER), Command.approve(manager("SG"), new BigDecimal("0"))))
        .as("amount rule").isEqualTo(ErrorCode.INVALID_AMOUNT);
    assertThat(codeOf(snap(ClaimStatus.ASSESSED, OFFICER), Command.approve(officer(OFFICER, "SG"), new BigDecimal("10000.01"))))
        .as("authority last").isEqualTo(ErrorCode.AUTHORITY_EXCEEDED);
    assertThat(codeOf(snap(ClaimStatus.ASSESSED, OFFICER), Command.approve(officer(OFFICER, "SG"), new BigDecimal("0"))))
        .as("amount before authority").isEqualTo(ErrorCode.INVALID_AMOUNT);
  }

  @Test
  void theLimitIsReadForTheClaimsMarket() {
    Actor both = new Actor(OFFICER, Role.OFFICER, Set.of("SG", "ID"),
        Map.of("SG", new BigDecimal("10000"), "ID", new BigDecimal("200000000")));
    ClaimSnapshot sg = snap(ClaimStatus.ASSESSED, OFFICER);
    assertThat(codeOf(sg, Command.approve(both, new BigDecimal("10000.01")))).isEqualTo(ErrorCode.AUTHORITY_EXCEEDED);
    ClaimSnapshot id = new ClaimSnapshot(ClaimStatus.ASSESSED, HOLDER, "ID", "IDR",
        new BigDecimal("500000000"), OFFICER, null);
    assertThat(ClaimDecider.decide(id, Command.approve(both, new BigDecimal("150000000"))).status())
        .isEqualTo(ClaimStatus.APPROVED);
    // a market without an entry means no limit: no authority for an officer
    Actor onlySg = new Actor(OFFICER, Role.OFFICER, Set.of("SG", "ID"), Map.of("SG", new BigDecimal("10000")));
    assertThat(codeOf(id, Command.approve(onlySg, new BigDecimal("1")))).isEqualTo(ErrorCode.AUTHORITY_EXCEEDED);
  }

  @Test
  void claimantCannotDoStaffActions() {
    Actor owner = new Actor(HOLDER, Role.CLAIMANT, Set.of("SG"), Map.of());
    for (ClaimAction a : ClaimAction.values()) {
      if (a == ClaimAction.ANSWER_INFO) continue;
      assertThat(ActorPermissions.check(owner, ctx(OFFICER), a, null))
          .as(a.name()).isInstanceOf(PermissionResult.NotFound.class);
    }
  }

  // ---- financial position ----

  @Test
  void financialPositionIsDerivedFromTheLedger() {
    FinancialPosition empty = FinancialPosition.from(List.of());
    assertThat(empty.reserve()).isEqualByComparingTo("0");
    assertThat(empty.incurred()).isEqualByComparingTo("0");

    FinancialPosition p =
        FinancialPosition.from(
            List.of(
                new LedgerEntry(LedgerType.RESERVE_SET, new BigDecimal("1000")),
                new LedgerEntry(LedgerType.RESERVE_SET, new BigDecimal("800")),
                new LedgerEntry(LedgerType.PAYMENT, new BigDecimal("300"))));
    assertThat(p.reserve()).isEqualByComparingTo("800");
    assertThat(p.paid()).isEqualByComparingTo("300");
    assertThat(p.outstanding()).isEqualByComparingTo("500");
    assertThat(p.incurred()).isEqualByComparingTo("800");
  }

  // ---- submission validation ----

  static final ZoneId SG = ZoneId.of("Asia/Singapore");
  static final Clock NOW =
      Clock.fixed(Instant.parse("2026-11-04T04:00:00Z"), ZoneOffset.UTC); // 12:00 SGT Wed

  static PolicyFacts policy() {
    return new PolicyFacts(HOLDER, Line.MOTOR, "SGD", SG, LocalDate.of(2025, 1, 1), LocalDate.of(2030, 12, 31));
  }

  static SubmissionInput motor(LocalDateTime incident, ClaimType type, LossDetails loss) {
    return new SubmissionInput(HOLDER, Line.MOTOR, type, incident, new BigDecimal("1200.00"), loss, "+6591234567", "dented door");
  }

  static final MotorLoss GOOD_MOTOR = new MotorLoss("SGX1234A", "Tan Ah Kow", null, false, null, null, "Orchard Road");

  static List<ErrorCode> codes(SubmissionInput in, PolicyFacts p) {
    return SubmissionValidator.validate(in, p, NOW).stream().map(Violation::code).toList();
  }

  @Test
  void validMotorSubmissionHasNoViolations() {
    assertThat(codes(motor(LocalDateTime.parse("2026-11-03T09:00:00"), ClaimType.OWN_DAMAGE, GOOD_MOTOR), policy()))
        .isEmpty();
  }

  @Test
  void incidentMustNotBeInTheFutureInTheMarketZone() {
    // 12:00 SGT now. 13:00 SGT is the future even though the UTC date is the same.
    assertThat(codes(motor(LocalDateTime.parse("2026-11-04T13:00:00"), ClaimType.OWN_DAMAGE, GOOD_MOTOR), policy()))
        .containsExactly(ErrorCode.INCIDENT_IN_FUTURE);
    assertThat(codes(motor(LocalDateTime.parse("2026-11-04T11:59:00"), ClaimType.OWN_DAMAGE, GOOD_MOTOR), policy()))
        .isEmpty();
  }

  @Test
  void incidentMustFallInsideThePolicyDatesInTheMarketZone() {
    assertThat(codes(motor(LocalDateTime.parse("2024-12-31T23:59:00"), ClaimType.OWN_DAMAGE, GOOD_MOTOR), policy()))
        .containsExactly(ErrorCode.INCIDENT_OUTSIDE_POLICY_PERIOD);
    assertThat(codes(motor(LocalDateTime.parse("2025-01-01T00:00:00"), ClaimType.OWN_DAMAGE, GOOD_MOTOR), policy()))
        .isEmpty();
  }

  @Test
  void policyMustBelongToTheClaimantAndMatchTheLine() {
    PolicyFacts other = new PolicyFacts(UUID.randomUUID(), Line.MOTOR, "SGD", SG, LocalDate.of(2025, 1, 1), LocalDate.of(2030, 12, 31));
    assertThat(codes(motor(LocalDateTime.parse("2026-11-03T09:00:00"), ClaimType.OWN_DAMAGE, GOOD_MOTOR), other))
        .contains(ErrorCode.NOT_FOUND);
    PolicyFacts property = new PolicyFacts(HOLDER, Line.PROPERTY, "SGD", SG, LocalDate.of(2025, 1, 1), LocalDate.of(2030, 12, 31));
    assertThat(codes(motor(LocalDateTime.parse("2026-11-03T09:00:00"), ClaimType.OWN_DAMAGE, GOOD_MOTOR), property))
        .contains(ErrorCode.POLICY_MISMATCH);
  }

  @Test
  void claimTypeMustBeValidForTheLine() {
    assertThat(codes(motor(LocalDateTime.parse("2026-11-03T09:00:00"), ClaimType.FIRE, GOOD_MOTOR), policy()))
        .contains(ErrorCode.INVALID_CLAIM_TYPE);
    for (ClaimType t : new ClaimType[] {ClaimType.OWN_DAMAGE, ClaimType.THIRD_PARTY_DAMAGE, ClaimType.TOTAL_LOSS, ClaimType.WINDSCREEN, ClaimType.THEFT}) {
      assertThat(Line.MOTOR.allows(t)).as(t.name()).isTrue();
    }
    for (ClaimType t : new ClaimType[] {ClaimType.FIRE, ClaimType.WATER_DAMAGE, ClaimType.STORM, ClaimType.THEFT, ClaimType.OTHER}) {
      assertThat(Line.PROPERTY.allows(t)).as(t.name()).isTrue();
    }
    assertThat(Line.PROPERTY.allows(ClaimType.OWN_DAMAGE)).isFalse();
    assertThat(Line.MOTOR.allows(ClaimType.OTHER)).isFalse();
  }

  @Test
  void motorNeedsRegistrationAndDriver() {
    LocalDateTime t = LocalDateTime.parse("2026-11-03T09:00:00");
    assertThat(codes(motor(t, ClaimType.OWN_DAMAGE, new MotorLoss("", "Tan", null, false, null, null, "Orchard Rd")), policy()))
        .containsExactly(ErrorCode.VALIDATION_FAILED);
    assertThat(codes(motor(t, ClaimType.OWN_DAMAGE, new MotorLoss("SGX1", " ", null, false, null, null, "Orchard Rd")), policy()))
        .containsExactly(ErrorCode.VALIDATION_FAILED);
    assertThat(codes(motor(t, ClaimType.OWN_DAMAGE, new MotorLoss("SGX1", "Tan", null, false, null, null, " ")), policy()))
        .containsExactly(ErrorCode.VALIDATION_FAILED);
    assertThat(codes(motor(t, ClaimType.OWN_DAMAGE, null), policy()))
        .containsExactly(ErrorCode.VALIDATION_FAILED);
    assertThat(codes(motor(t, ClaimType.OWN_DAMAGE, new PropertyLoss("1 Road", "fire", null)), policy()))
        .containsExactly(ErrorCode.VALIDATION_FAILED);
  }

  @Test
  void propertyNeedsAddressAndCause() {
    PolicyFacts p = new PolicyFacts(HOLDER, Line.PROPERTY, "SGD", SG, LocalDate.of(2025, 1, 1), LocalDate.of(2030, 12, 31));
    LocalDateTime t = LocalDateTime.parse("2026-11-03T09:00:00");
    SubmissionInput good = new SubmissionInput(HOLDER, Line.PROPERTY, ClaimType.FIRE, t, new BigDecimal("5000"), new PropertyLoss("1 Orchard Road", "Kitchen fire", "OWNER"), "+6591234567", "fire");
    assertThat(codes(good, p)).isEmpty();
    SubmissionInput noAddr = new SubmissionInput(HOLDER, Line.PROPERTY, ClaimType.FIRE, t, new BigDecimal("5000"), new PropertyLoss(null, "fire", null), "+6591234567", "fire");
    assertThat(codes(noAddr, p)).containsExactly(ErrorCode.VALIDATION_FAILED);
    SubmissionInput noCause = new SubmissionInput(HOLDER, Line.PROPERTY, ClaimType.FIRE, t, new BigDecimal("5000"), new PropertyLoss("1 Road", "", null), "+6591234567", "fire");
    assertThat(codes(noCause, p)).containsExactly(ErrorCode.VALIDATION_FAILED);
  }

  @Test
  void estimateMustBeNonNegativeAndFitTheCurrency() {
    SubmissionInput neg = new SubmissionInput(HOLDER, Line.MOTOR, ClaimType.OWN_DAMAGE, LocalDateTime.parse("2026-11-03T09:00:00"), new BigDecimal("-1"), GOOD_MOTOR, "+65", "x");
    assertThat(codes(neg, policy())).containsExactly(ErrorCode.INVALID_AMOUNT);
    SubmissionInput zero = new SubmissionInput(HOLDER, Line.MOTOR, ClaimType.OWN_DAMAGE, LocalDateTime.parse("2026-11-03T09:00:00"), BigDecimal.ZERO, GOOD_MOTOR, "+65", "x");
    assertThat(codes(zero, policy())).isEmpty();
  }
}
