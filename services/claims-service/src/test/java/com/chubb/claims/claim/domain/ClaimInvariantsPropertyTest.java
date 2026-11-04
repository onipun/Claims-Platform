package com.chubb.claims.claim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.claims.claim.Claim;
import com.chubb.claims.claim.ClaimActivity;
import com.chubb.claims.claim.FinancialEntry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The invariants of LIFECYCLE.md over random command sequences on the real {@link Claim} entity (no
 * database), including illegal commands, bad amounts, wrong actors and conflicts of interest. Version
 * and outbox rows (7, 8) need a database: {@code ClaimInvariantsDbTest} checks them from SQL. Here the
 * dirty-row mechanism behind 7 is checked: a success always moves {@code updatedAt} forward, a failure
 * never touches it.
 */
class ClaimInvariantsPropertyTest {

  static final UUID HOLDER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  static final UUID OFFICER_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  static final UUID OFFICER_B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  static final UUID MANAGER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  static final UUID OUTSIDER = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  static final UUID OTHER_CLAIMANT = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

  static final Actor CLAIMANT = new Actor(HOLDER, Role.CLAIMANT, Set.of("SG"), Map.of());
  static final Actor STRANGER = new Actor(OTHER_CLAIMANT, Role.CLAIMANT, Set.of("SG"), Map.of());
  static final Actor A = new Actor(OFFICER_A, Role.OFFICER, Set.of("SG"), new BigDecimal("10000"));
  static final Actor B = new Actor(OFFICER_B, Role.OFFICER, Set.of("SG"), new BigDecimal("100"));
  static final Actor M = new Actor(MANAGER, Role.MANAGER, Set.of("SG"), (java.math.BigDecimal) null);
  static final Actor OUT = new Actor(OUTSIDER, Role.OFFICER, Set.of("HK"), new BigDecimal("100000"));
  static final Actor HOLDER_AS_STAFF = new Actor(HOLDER, Role.OFFICER, Set.of("SG"), new BigDecimal("100000"));

  static final BigDecimal LIMIT = new BigDecimal("50000");
  static final BigDecimal DEDUCTIBLE = new BigDecimal("500");

  enum ActorKind { ASSIGNED, MANAGER, A, B, OUTSIDER, HOLDER_AS_STAFF, CLAIMANT, STRANGER }

  enum AmountKind { VALID, SMALL, ZERO, NEGATIVE, OVER_LIMIT, BAD_SCALE, MATCH_APPROVED }

  enum TargetKind { A, B, MANAGER, OUTSIDER, HOLDER, CLAIMANT }

  record Step(
      ClaimAction action, ActorKind actor, AmountKind amount, int pct, TargetKind target,
      RejectReason reason, boolean explain) {

    @Override
    public String toString() {
      return action + " by " + actor + " amount=" + amount + "@" + pct + "% target=" + target;
    }
  }

  static final Instant T0 = Instant.parse("2026-03-11T02:00:00Z");

  static Claim newClaim(String currency, BigDecimal estimate) {
    return Claim.submit(new Claim.NewClaim("CLM-SG-2026-000001", UUID.randomUUID(), HOLDER, "SG", Line.MOTOR,
        ClaimType.OWN_DAMAGE, LocalDateTime.parse("2026-03-10T09:00:00"), "Asia/Singapore", T0, false, null,
        "+6591234567", "dent", new MotorLoss("SGX1234A", "Tan", null, false, null), estimate, LIMIT, DEDUCTIBLE,
        currency, T0.plusSeconds(86400), T0.plusSeconds(14 * 86400), null, null), T0);
  }

  static final class Model {
    final Claim claim;
    final String currency;
    int succeeded;
    int failed;
    Instant now = T0;

    final List<Step> trail = new ArrayList<>();

    Model(String currency, BigDecimal estimate) {
      this.currency = currency;
      this.claim = newClaim(currency, estimate);
      checkInvariants();
    }

    boolean hasOpenInfoRequest() {
      return claim.getInfoRequests().stream().anyMatch(r -> r.isOpen());
    }

    Actor actor(ActorKind k) {
      return switch (k) {
        case ASSIGNED ->
            OFFICER_A.equals(claim.getAssignedOfficerId()) ? A
                : OFFICER_B.equals(claim.getAssignedOfficerId()) ? B : M;
        case MANAGER -> M;
        case A -> A;
        case B -> B;
        case OUTSIDER -> OUT;
        case HOLDER_AS_STAFF -> HOLDER_AS_STAFF;
        case CLAIMANT -> CLAIMANT;
        case STRANGER -> STRANGER;
      };
    }

    BigDecimal amount(Step s) {
      int scale = Money.scaleOf(currency);
      return switch (s.amount()) {
        case VALID -> LIMIT.multiply(BigDecimal.valueOf(s.pct())).divide(BigDecimal.valueOf(100), scale, RoundingMode.DOWN);
        case SMALL -> BigDecimal.valueOf(s.pct() + 1L).setScale(scale);
        case ZERO -> BigDecimal.ZERO;
        case NEGATIVE -> BigDecimal.valueOf(-1 - s.pct()).setScale(scale);
        case OVER_LIMIT -> LIMIT.add(BigDecimal.valueOf(s.pct() + 1L));
        case BAD_SCALE -> new BigDecimal("10.001");
        case MATCH_APPROVED ->
            claim.getApprovedAmount() != null ? claim.getApprovedAmount() : BigDecimal.TEN.setScale(scale);
      };
    }

    TargetUser target(TargetKind k) {
      return switch (k) {
        case A -> new TargetUser(OFFICER_A, Role.OFFICER, Set.of("SG"));
        case B -> new TargetUser(OFFICER_B, Role.OFFICER, Set.of("SG"));
        case MANAGER -> new TargetUser(MANAGER, Role.MANAGER, Set.of("SG"));
        case OUTSIDER -> new TargetUser(OUTSIDER, Role.OFFICER, Set.of("HK"));
        case HOLDER -> new TargetUser(HOLDER, Role.OFFICER, Set.of("SG"));
        case CLAIMANT -> new TargetUser(OTHER_CLAIMANT, Role.CLAIMANT, Set.of("SG"));
      };
    }

    Command command(Step s) {
      Actor who = actor(s.actor());
      return switch (s.action()) {
        case PICK_UP -> Command.pickUp(who);
        case ASSIGN -> Command.assign(who, target(s.target()));
        case ADD_NOTE -> Command.note(who, "note");
        case REQUEST_INFO -> Command.requestInfo(who, "please send photos");
        case ANSWER_INFO -> Command.answerInfo(who, "here are the photos");
        case ASSESS -> Command.assess(who, amount(s));
        case APPROVE -> Command.approve(who, amount(s));
        case REJECT -> Command.reject(who, s.reason(), s.explain() ? "Explained to claimant." : " ");
        case SETTLE -> Command.settle(who, amount(s));
      };
    }

    void attempt(Step s) {
      trail.add(s);
      now = now.plusSeconds(1);
      ClaimStatus statusBefore = claim.getStatus();
      Instant updatedBefore = claim.getUpdatedAt();
      int ledgerBefore = claim.getLedger().size();
      int activitiesBefore = claim.getActivities().size();
      int requestsBefore = claim.getInfoRequests().size();
      boolean openBefore = hasOpenInfoRequest();
      BigDecimal reserveBefore = claim.getReserve();
      BigDecimal paidBefore = claim.getPaid();
      UUID officerBefore = claim.getAssignedOfficerId();

      Command cmd = command(s);
      try {
        claim.execute(cmd, now);
        succeeded++;
        // 7 (mechanism): a success always dirties the row, so the version moves by exactly one
        assertThat(claim.getUpdatedAt()).as("7: updatedAt moves").isAfter(updatedBefore);
        // 6: an activity for each command, recording any status change
        assertThat(claim.getActivities()).as("6: activity").hasSize(activitiesBefore + 1);
        ClaimActivity last = claim.getActivities().get(claim.getActivities().size() - 1);
        assertThat(last.getFromStatus()).isEqualTo(statusBefore);
        assertThat(last.getToStatus()).isEqualTo(claim.getStatus());
        assertThat(last.getActorId()).isEqualTo(cmd.actor().id());
        // 1: nothing leaves a terminal state, so no command can have succeeded from one
        assertThat(statusBefore.isTerminal()).as("1: succeeded from terminal").isFalse();
        // 5: information requests. The first request opens one; an answer closes the open one only.
        switch (s.action()) {
          case REQUEST_INFO -> {
            assertThat(openBefore).as("5: a request cannot open while one is open").isFalse();
            assertThat(claim.getInfoRequests()).hasSize(requestsBefore + 1);
            assertThat(claim.getInfoRequests().get(requestsBefore).isOpen()).isTrue();
            assertThat(claim.getInfoRequests().get(requestsBefore).getQuestion()).isEqualTo("please send photos");
          }
          case ANSWER_INFO -> {
            assertThat(openBefore).as("5: an answer needs an open request").isTrue();
            assertThat(claim.getInfoRequests()).hasSize(requestsBefore);
            assertThat(hasOpenInfoRequest()).isFalse();
            assertThat(claim.getInfoRequests().get(requestsBefore - 1).getAnswer()).isEqualTo("here are the photos");
            assertThat(claim.getStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
          }
          default -> assertThat(claim.getInfoRequests()).hasSize(requestsBefore);
        }
      } catch (DomainException e) {
        failed++;
        assertThat(e.code()).isNotNull();
        // a rejected command changes nothing
        assertThat(claim.getStatus()).isEqualTo(statusBefore);
        assertThat(claim.getUpdatedAt()).isEqualTo(updatedBefore);
        assertThat(claim.getLedger()).hasSize(ledgerBefore);
        assertThat(claim.getActivities()).hasSize(activitiesBefore);
        assertThat(claim.getInfoRequests()).hasSize(requestsBefore);
        assertThat(hasOpenInfoRequest()).isEqualTo(openBefore);
        assertThat(claim.getReserve()).isEqualByComparingTo(reserveBefore);
        assertThat(claim.getPaid()).isEqualByComparingTo(paidBefore);
        assertThat(claim.getAssignedOfficerId()).isEqualTo(officerBefore);
      }
      if (statusBefore.isTerminal()) {
        assertThat(claim.getStatus()).as("1: terminal never changes").isEqualTo(statusBefore);
      }
      checkInvariants();
    }

    void checkInvariants() {
      FinancialPosition derived = claim.position();
      // 2
      assertThat(derived.outstanding()).as("2: outstanding >= 0").isGreaterThanOrEqualTo(BigDecimal.ZERO);
      // 3
      assertThat(claim.getReserve()).as("3: reserve <= limit").isLessThanOrEqualTo(claim.getCoverageLimit());
      assertThat(derived.reserve()).isLessThanOrEqualTo(claim.getCoverageLimit());
      // 4
      assertThat(claim.getReserve()).as("4: reserve matches ledger").isEqualByComparingTo(derived.reserve());
      assertThat(claim.getPaid()).as("4: paid matches ledger").isEqualByComparingTo(derived.paid());
      for (FinancialEntry e : claim.getLedger()) assertThat(e.getCurrency()).isEqualTo(currency);
      // 5
      assertThat(claim.getInfoRequests().stream().filter(r -> r.isOpen()).count())
          .as("5: open info requests").isLessThanOrEqualTo(1);
      assertThat(hasOpenInfoRequest()).isEqualTo(claim.getStatus() == ClaimStatus.AWAITING_INFO);
      // 6: the activity trail is a connected chain ending at the current status
      ClaimStatus cursor = null;
      for (ClaimActivity a : claim.getActivities()) {
        if (cursor != null) assertThat(a.getFromStatus()).as("6: chain").isEqualTo(cursor);
        cursor = a.getToStatus();
      }
      assertThat(cursor).as("6: trail ends at current status").isEqualTo(claim.getStatus());
    }
  }

  /** Fixed by default so a failure reproduces; override with -Dclaims.property.baseSeed=... */
  static final long BASE_SEED = Long.getLong("claims.property.baseSeed", 20260311L);
  static final int SEQUENCES = Integer.getInteger("claims.property.sequences", 1500);

  private static <T> T weighted(Random r, Map<T, Integer> weights) {
    int total = weights.values().stream().mapToInt(Integer::intValue).sum();
    int roll = r.nextInt(total);
    for (Map.Entry<T, Integer> e : weights.entrySet()) {
      roll -= e.getValue();
      if (roll < 0) return e.getKey();
    }
    throw new IllegalStateException();
  }

  @SafeVarargs
  private static <T extends Enum<T>> Map<T, Integer> w(Class<T> type, Object... pairs) {
    Map<T, Integer> m = new EnumMap<>(type);
    for (int i = 0; i < pairs.length; i += 2) m.put(type.cast(pairs[i]), (Integer) pairs[i + 1]);
    return m;
  }

  static final Map<ClaimAction, Integer> ACTIONS = w(ClaimAction.class,
      ClaimAction.PICK_UP, 3, ClaimAction.ASSIGN, 3, ClaimAction.ADD_NOTE, 2, ClaimAction.REQUEST_INFO, 2,
      ClaimAction.ANSWER_INFO, 3, ClaimAction.ASSESS, 5, ClaimAction.APPROVE, 5, ClaimAction.REJECT, 1,
      ClaimAction.SETTLE, 5);
  static final Map<ActorKind, Integer> ACTORS = w(ActorKind.class,
      ActorKind.ASSIGNED, 8, ActorKind.MANAGER, 4, ActorKind.A, 2, ActorKind.B, 1, ActorKind.OUTSIDER, 1,
      ActorKind.HOLDER_AS_STAFF, 1, ActorKind.CLAIMANT, 3, ActorKind.STRANGER, 1);
  static final Map<AmountKind, Integer> AMOUNTS = w(AmountKind.class,
      AmountKind.VALID, 10, AmountKind.SMALL, 3, AmountKind.ZERO, 1, AmountKind.NEGATIVE, 1,
      AmountKind.OVER_LIMIT, 2, AmountKind.BAD_SCALE, 1, AmountKind.MATCH_APPROVED, 10);

  static Step randomStep(Random r) {
    return new Step(weighted(r, ACTIONS), weighted(r, ACTORS), weighted(r, AMOUNTS), r.nextInt(101),
        TargetKind.values()[r.nextInt(TargetKind.values().length)],
        RejectReason.values()[r.nextInt(RejectReason.values().length)], r.nextInt(6) != 0);
  }

  static int floor(int atDefaultCount) {
    return atDefaultCount * SEQUENCES / 1500;
  }

  @Test
  void invariantsHoldOverRandomCommandSequencesOnTheRealClaim() {
    Map<ClaimStatus, Integer> distribution = new EnumMap<>(ClaimStatus.class);
    for (int i = 0; i < SEQUENCES; i++) {
      long seed = BASE_SEED + i;
      Random r = new Random(seed);
      String currency = r.nextBoolean() ? "SGD" : "IDR";
      int estimatePct = r.nextInt(151);
      BigDecimal estimate = LIMIT.multiply(BigDecimal.valueOf(estimatePct))
          .divide(BigDecimal.valueOf(100), Money.scaleOf(currency), RoundingMode.DOWN);
      int length = 5 + r.nextInt(56);
      Model model = null;
      try {
        model = new Model(currency, estimate);
        for (int n = 0; n < length; n++) model.attempt(randomStep(r));
      } catch (Throwable t) {
        throw new AssertionError("Invariant failed. seed=" + seed + " currency=" + currency + " estimate="
            + estimate + " commands so far=" + (model == null ? "[]" : model.trail) + " cause: " + t.getMessage(), t);
      }
      distribution.merge(model.claim.getStatus(), 1, Integer::sum);
    }
    System.out.println("Random-sequence final status distribution (" + SEQUENCES + " sequences, baseSeed="
        + BASE_SEED + "): " + distribution);
    // Floors are about 60% of what the fixed default seeds produce (SETTLED 163, REJECTED 219, APPROVED 178,
    // AWAITING_INFO 426 of 1500), scaled to the sequence count, so a generator that stops reaching a state fails.
    assertThat(distribution.getOrDefault(ClaimStatus.SETTLED, 0)).as("SETTLED coverage").isGreaterThanOrEqualTo(floor(100));
    assertThat(distribution.getOrDefault(ClaimStatus.REJECTED, 0)).as("REJECTED coverage").isGreaterThanOrEqualTo(floor(130));
    assertThat(distribution.getOrDefault(ClaimStatus.APPROVED, 0)).as("APPROVED coverage").isGreaterThanOrEqualTo(floor(100));
    assertThat(distribution.getOrDefault(ClaimStatus.AWAITING_INFO, 0)).as("AWAITING_INFO coverage")
        .isGreaterThanOrEqualTo(floor(250));
  }
}
