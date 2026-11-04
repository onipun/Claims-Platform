package com.chubb.claims.claim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.claims.claim.ClaimSubmissionService;
import com.chubb.claims.claim.ClaimantClaimService;
import com.chubb.claims.claim.ETags;
import com.chubb.claims.claim.StaffClaimService;
import com.chubb.claims.claim.api.MotorDetails;
import com.chubb.claims.claim.api.SubmitClaimRequest;
import com.chubb.claims.claim.domain.ClaimInvariantsPropertyTest.ActorKind;
import com.chubb.claims.claim.domain.ClaimInvariantsPropertyTest.AmountKind;
import com.chubb.claims.claim.domain.ClaimInvariantsPropertyTest.Step;
import com.chubb.claims.claim.domain.ClaimInvariantsPropertyTest.TargetKind;
import com.chubb.claims.identity.AuthenticatedUser;
import com.chubb.claims.support.MutableClock;
import com.chubb.claims.support.TestInfra;
import com.chubb.claims.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The same seeds and step generator as {@link ClaimInvariantsPropertyTest}, but driven through the real
 * {@link StaffClaimService}, {@link ClaimantClaimService} and {@link ClaimSubmissionService} on a real
 * Postgres, with illegal commands, stale and missing If-Match mixed in. After every sequence the
 * invariants are read back with SQL: the version, one outbox row per version and none for failures, the
 * reserve and paid figures against the ledger, and an activity row per successful command.
 *
 * <p>40 sequences by default (each command is a real transaction); raise with
 * {@code -Dclaims.db.sequences=...}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfra.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClaimInvariantsDbTest {

  static final int SEQUENCES = Integer.getInteger("claims.db.sequences", 40);

  @Autowired JdbcTemplate jdbc;
  @Autowired StaffClaimService staff;
  @Autowired ClaimantClaimService claimant;
  @Autowired ClaimSubmissionService submission;
  @Autowired MutableClock clock;

  /** Another test class sharing this context may have moved the clock: start from the known instant. */
  @BeforeEach
  void resetClock() {
    clock.set(TestInfra.DEFAULT_NOW);
  }

  /**
   * R10 (D2): the {@code @BeforeEach} reset above matters. This test leaves the shared clock before the
   * incident; the next one only passes because the reset ran in between. Without the reset, its
   * submission is refused as INCIDENT_IN_FUTURE.
   */
  @Test
  @Order(1)
  void aClockBeforeTheIncidentRefusesTheSubmissionAndIsLeftThere() {
    clock.set(Instant.parse("2026-03-09T00:00:00Z")); // before the 2026-03-10 09:00 SGT incident
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> submit(WORLDS.get(0), new BigDecimal("1000")))
        .isInstanceOfSatisfying(SubmissionRejectedException.class,
            e -> assertThat(e.violations()).extracting(Violation::code).containsExactly(ErrorCode.INCIDENT_IN_FUTURE));
  }

  @Test
  @Order(2)
  void theResetPutTheClockBackSoTheSameSubmissionNowSucceeds() {
    assertThat(clock.instant()).as("the previous test left the clock at 2026-03-09").isEqualTo(TestInfra.DEFAULT_NOW);
    assertThat(submit(WORLDS.get(0), new BigDecimal("1000"))).isNotNull();
  }

  /** Everything a refused command must leave alone, read from SQL in one place. */
  static final String STATE_SQL = "select c.version, c.status, c.reserve, c.paid, c.approved_amount, c.assigned_officer_id, "
      + "(select count(*) from outbox_event o where o.claim_id = c.id) outbox, "
      + "(select count(*) from claim_activity a where a.claim_id = c.id) activity, "
      + "(select count(*) from financial_entry f where f.claim_id = c.id) ledger, "
      + "(select count(*) from info_request i where i.claim_id = c.id) info_rows, "
      + "(select count(*) from info_request i where i.claim_id = c.id and i.answered_at is null) info_open "
      + "from claim c where c.id = ?";

  /** Who plays each part for one claim. */
  record World(String name, String holder, String policy, String currency, BigDecimal coverage, String a, String b,
      String manager, String outsider, String stranger, String holderAsStaff) {}

  static final List<World> WORLDS = List.of(
      new World("SG", "claimant.sg@demo.test", "MOT-SG-100001", "SGD", new BigDecimal("100000"),
          "officer.sg@demo.test", "officer2.sg@demo.test", "manager.sg@demo.test", "officer.hk@demo.test",
          "claimant2.sg@demo.test", "claimant.sg@demo.test"),
      // officer2.sg holds this policy: every staff action by officer2.sg is a conflict of interest
      new World("SG-staff-holder", "officer2.sg@demo.test", "MOT-SG-100005", "SGD", new BigDecimal("60000"),
          "officer.sg@demo.test", "officer2.sg@demo.test", "manager.sg@demo.test", "officer.hk@demo.test",
          "claimant2.sg@demo.test", "officer2.sg@demo.test"),
      new World("ID", "claimant.id@demo.test", "MOT-ID-400001", "IDR", new BigDecimal("500000000"),
          "officer.id@demo.test", "officer.id@demo.test", "manager.apac@demo.test", "officer.sg@demo.test",
          "claimant.my@demo.test", "claimant.id@demo.test"));

  AuthenticatedUser user(String email) {
    Map<String, Object> u = jdbc.queryForMap("select id, role, name from app_user where email = ?", email);
    UUID id = (UUID) u.get("id");
    Set<String> markets = Set.copyOf(jdbc.queryForList("select market_code from user_market where user_id = ?",
        String.class, id));
    return new AuthenticatedUser(id, Role.valueOf((String) u.get("role")), markets, (String) u.get("name"));
  }

  UUID submit(World w, BigDecimal estimate) {
    AuthenticatedUser holder = user(w.holder());
    UUID policy = jdbc.queryForObject("select id from policy where policy_number = ?", UUID.class, w.policy());
    SubmitClaimRequest req = new SubmitClaimRequest(policy, Line.MOTOR, ClaimType.OWN_DAMAGE,
        LocalDateTime.parse("2026-03-10T09:00:00"), estimate, "+6591234567", "Rear-ended", null,
        new MotorDetails("SGX1234A", "Tan", null, false, "PR-1", null, "Orchard Road"), null);
    return submission.submit(holder, UUID.randomUUID().toString(), req).claim().id();
  }

  static final String ROW_CHECK_SQL = "select "
      + "c.reserve <> coalesce((select amount from financial_entry where claim_id = ? and entry_type = 'RESERVE_SET' order by id desc limit 1), 0) reserve_ne_ledger, "
      + "c.paid <> coalesce((select sum(amount) from financial_entry where claim_id = ? and entry_type = 'PAYMENT'), 0) paid_ne_ledger, "
      + "coalesce((select amount from financial_entry where claim_id = ? and entry_type = 'RESERVE_SET' order by id desc limit 1), 0) > c.coverage_limit reserve_over_limit, "
      + "exists (select 1 from financial_entry where claim_id = ? and amount < 0) negative_amount, "
      + "(coalesce((select amount from financial_entry where claim_id = ? and entry_type = 'RESERVE_SET' order by id desc limit 1), 0) "
      + "  - coalesce((select sum(amount) from financial_entry where claim_id = ? and entry_type = 'PAYMENT'), 0)) < 0 or c.reserve - c.paid < 0 outstanding_negative, "
      + "(select count(*) from info_request where claim_id = c.id and answered_at is null) > 1 open_info_over_one "
      + "from claim c where c.id = ?";

  /** One sequence's state, read from SQL when needed. */
  final class Run {
    final World w;
    final UUID id;
    final Map<String, AuthenticatedUser> users = new java.util.HashMap<>();
    int succeeded;
    int failed;
    final Map<String, Integer> failureCodes = new TreeMap<>();

    Run(World w, UUID id) {
      this.w = w;
      this.id = id;
    }

    AuthenticatedUser u(String email) {
      return users.computeIfAbsent(email, ClaimInvariantsDbTest.this::user);
    }

    Map<String, Object> state() {
      return jdbc.queryForMap(STATE_SQL, id);
    }

    /**
     * D3: after every successful command, one SQL check. Returns the number of violations found, by rule.
     */
    void assertRowInvariantsAfterCommand() {
      Map<String, Object> v = jdbc.queryForMap(ROW_CHECK_SQL, id, id, id, id, id, id, id);
      assertThat(v.get("reserve_ne_ledger")).as("claim.reserve equals the latest RESERVE_SET").isEqualTo(false);
      assertThat(v.get("paid_ne_ledger")).as("claim.paid equals the sum of PAYMENT").isEqualTo(false);
      assertThat(v.get("reserve_over_limit")).as("latest RESERVE_SET <= coverage limit").isEqualTo(false);
      assertThat(v.get("negative_amount")).as("no negative ledger amount").isEqualTo(false);
      assertThat(v.get("outstanding_negative")).as("case outstanding >= 0").isEqualTo(false);
      assertThat(v.get("open_info_over_one")).as("open info_request rows <= 1").isEqualTo(false);
    }

    long version() {
      return jdbc.queryForObject("select version from claim where id = ?", Long.class, id);
    }

    long outbox() {
      return jdbc.queryForObject("select count(*) from outbox_event where claim_id = ?", Long.class, id);
    }

    long activities() {
      return jdbc.queryForObject("select count(*) from claim_activity where claim_id = ?", Long.class, id);
    }

    long ledger() {
      return jdbc.queryForObject("select count(*) from financial_entry where claim_id = ?", Long.class, id);
    }

    String status() {
      return jdbc.queryForObject("select status from claim where id = ?", String.class, id);
    }

    UUID assigned() {
      return jdbc.queryForObject("select assigned_officer_id from claim where id = ?", UUID.class, id);
    }

    BigDecimal approved() {
      return jdbc.queryForObject("select approved_amount from claim where id = ?", BigDecimal.class, id);
    }

    AuthenticatedUser actor(ActorKind k) {
      return switch (k) {
        case ASSIGNED -> {
          UUID a = assigned();
          AuthenticatedUser ua = u(w.a());
          AuthenticatedUser ub = u(w.b());
          yield a == null ? u(w.manager()) : a.equals(ua.id()) ? ua : a.equals(ub.id()) ? ub : u(w.manager());
        }
        case MANAGER -> u(w.manager());
        case A -> u(w.a());
        case B -> u(w.b());
        case OUTSIDER -> u(w.outsider());
        case HOLDER_AS_STAFF -> u(w.holderAsStaff());
        case CLAIMANT -> u(w.holder());
        case STRANGER -> u(w.stranger());
      };
    }

    UUID target(TargetKind k) {
      return switch (k) {
        case A -> u(w.a()).id();
        case B -> u(w.b()).id();
        case MANAGER -> u(w.manager()).id();
        case OUTSIDER -> u(w.outsider()).id();
        case HOLDER -> u(w.holder()).id();
        case CLAIMANT -> u(w.stranger()).id();
      };
    }

    BigDecimal amount(Step s) {
      int scale = Money.scaleOf(w.currency());
      return switch (s.amount()) {
        case VALID -> w.coverage().multiply(BigDecimal.valueOf(s.pct()))
            .divide(BigDecimal.valueOf(100), scale, RoundingMode.DOWN);
        case SMALL -> BigDecimal.valueOf(s.pct() + 1L).setScale(scale);
        case ZERO -> BigDecimal.ZERO;
        case NEGATIVE -> BigDecimal.valueOf(-1 - s.pct()).setScale(scale);
        case OVER_LIMIT -> w.coverage().add(BigDecimal.valueOf(s.pct() + 1L));
        case BAD_SCALE -> new BigDecimal("10.001");
        case MATCH_APPROVED -> approved() != null ? approved() : BigDecimal.TEN.setScale(scale);
      };
    }

    /** Runs the step through the real service. Returns true if it succeeded. */
    boolean attempt(Step s, int ifMatchMode) {
      AuthenticatedUser who = actor(s.actor());
      String current = ETags.of(version());
      // 0: current (most), 1: stale, 2: missing
      String ifMatch = ifMatchMode == 1 ? ETags.of(version() + 7) : ifMatchMode == 2 ? null : current;
      Map<String, Object> before = state();
      long versionBefore = version();
      long outboxBefore = outbox();
      long activitiesBefore = activities();
      long ledgerBefore = ledger();
      String statusBefore = status();
      BigDecimal reserveBefore = jdbc.queryForObject("select reserve from claim where id = ?", BigDecimal.class, id);
      try {
        switch (s.action()) {
          case PICK_UP -> staff.pickUp(who, id, ifMatch);
          case ASSIGN -> staff.assign(who, id, ifMatch, target(s.target()));
          case ADD_NOTE -> staff.note(who, id, ifMatch, "note");
          case REQUEST_INFO -> staff.requestInfo(who, id, ifMatch, "please send photos");
          case ANSWER_INFO -> claimant.answerInfo(who, id, ifMatch, "here are the photos");
          case ASSESS -> staff.assess(who, id, ifMatch, amount(s));
          case APPROVE -> staff.approve(who, id, ifMatch, amount(s));
          case REJECT -> staff.reject(who, id, ifMatch, s.reason(), s.explain() ? "Explained to claimant." : " ");
          case SETTLE -> staff.settle(who, id, ifMatch, amount(s));
        }
        succeeded++;
        assertThat(ifMatchMode).as("a stale or missing If-Match never succeeds").isZero();
        assertThat(version()).as("+1 per success").isEqualTo(versionBefore + 1);
        assertThat(outbox()).as("one outbox row per success").isEqualTo(outboxBefore + 1);
        assertThat(activities()).as("one activity row per success").isEqualTo(activitiesBefore + 1);
        assertThat(statusBefore).as("1: nothing succeeds from a terminal state")
            .isNotIn(ClaimStatus.SETTLED.name(), ClaimStatus.REJECTED.name());
        Map<String, Object> last = jdbc.queryForMap(
            "select activity_type, actor_id, from_status, to_status from claim_activity where claim_id = ? "
                + "order by id desc limit 1", id);
        assertThat(last.get("activity_type")).isEqualTo(s.action().name());
        assertThat(last.get("actor_id")).isEqualTo(who.id());
        assertThat(last.get("from_status")).isEqualTo(statusBefore);
        assertThat(last.get("to_status")).isEqualTo(status());
        assertRowInvariantsAfterCommand();
        return true;
      } catch (DomainException | ApiException e) {
        failed++;
        failureCodes.merge(e instanceof DomainException d ? d.code().name() : ((ApiException) e).code(), 1, Integer::sum);
        assertThat(version()).as("a failure changes no version").isEqualTo(versionBefore);
        assertThat(outbox()).as("no outbox row for a failure").isEqualTo(outboxBefore);
        assertThat(activities()).isEqualTo(activitiesBefore);
        assertThat(ledger()).isEqualTo(ledgerBefore);
        assertThat(status()).isEqualTo(statusBefore);
        assertThat(jdbc.queryForObject("select reserve from claim where id = ?", BigDecimal.class, id))
            .isEqualByComparingTo(reserveBefore);
        assertThat(state()).as("a failed command changes nothing: version, status, reserve, paid, approved_amount, "
            + "assigned officer, outbox, activity, ledger and info_request rows").isEqualTo(before);
        return false;
      }
    }

    /** The invariants, from SQL only. */
    void assertInvariants() {
      assertThat(version()).as("claim.version = number of successful commands").isEqualTo(succeeded);
      assertThat(jdbc.queryForList("select version from outbox_event where claim_id = ? order by version", Long.class, id))
          .as("outbox rows are versions 0..N, one per successful command")
          .isEqualTo(LongStream.rangeClosed(0, succeeded).boxed().toList());
      assertThat(jdbc.queryForObject("select count(*) from outbox_event where claim_id = ? "
          + "and (payload->>'claimVersion')::bigint <> version", Long.class, id)).isZero();

      Map<String, Object> c = jdbc.queryForMap(
          "select reserve, paid, coverage_limit, status, currency from claim where id = ?", id);
      BigDecimal ledgerReserve = jdbc.queryForObject(
          "select coalesce((select amount from financial_entry where claim_id = ? and entry_type = 'RESERVE_SET' "
              + "order by id desc limit 1), 0)", BigDecimal.class, id);
      BigDecimal ledgerPaid = jdbc.queryForObject(
          "select coalesce(sum(amount), 0) from financial_entry where claim_id = ? and entry_type = 'PAYMENT'",
          BigDecimal.class, id);
      assertThat((BigDecimal) c.get("reserve")).as("4: reserve = latest RESERVE_SET").isEqualByComparingTo(ledgerReserve);
      assertThat((BigDecimal) c.get("paid")).as("4: paid = sum of PAYMENT").isEqualByComparingTo(ledgerPaid);
      assertThat(ledgerReserve.subtract(ledgerPaid)).as("2: outstanding >= 0").isGreaterThanOrEqualTo(BigDecimal.ZERO);
      assertThat(ledgerReserve).as("3: reserve <= coverage limit")
          .isLessThanOrEqualTo((BigDecimal) c.get("coverage_limit"));
      assertThat(jdbc.queryForObject("select count(*) from financial_entry where claim_id = ? and currency <> ?",
          Long.class, id, c.get("currency"))).as("ledger currency = claim currency").isZero();

      // 6: one activity row for the submission and one for each successful command, chained to the status
      List<Map<String, Object>> trail = jdbc.queryForList(
          "select from_status, to_status from claim_activity where claim_id = ? order by id", id);
      assertThat(trail).as("6: activity rows").hasSize(succeeded + 1);
      String cursor = null;
      for (Map<String, Object> a : trail) {
        if (cursor != null) assertThat(a.get("from_status")).as("6: chain").isEqualTo(cursor);
        cursor = (String) a.get("to_status");
      }
      assertThat(cursor).as("6: trail ends at the status").isEqualTo(c.get("status"));
      // 5: at most one open information request, and exactly when the claim waits
      long open = jdbc.queryForObject(
          "select count(*) from info_request where claim_id = ? and answered_at is null", Long.class, id);
      assertThat(open).isEqualTo("AWAITING_INFO".equals(c.get("status")) ? 1 : 0);
    }
  }

  /** Share of commands drawn from the state-aware generator; the rest come from the blind one. */
  static final int LEGAL_PERCENT = 85;
  /** Every action is tried once against a claim that reached SETTLED or REJECTED. */
  static final int ACTIONS = ClaimAction.values().length;

  /**
   * D4: on a terminal claim run all 9 actions once each as an authorised actor (the assigned officer, or the
   * manager when nobody is assigned; the claimant for ANSWER_INFO) with the current If-Match and a valid body.
   * Each must be refused with ILLEGAL_TRANSITION and leave version, status, ledger, activity and outbox alone.
   */
  int probeTerminal(Run run) {
    AuthenticatedUser officer = run.actor(ActorKind.ASSIGNED);
    int probed = 0;
    int expected = ACTIONS;
    for (ClaimAction action : ClaimAction.values()) {
      // In the world where the policyholder is a staff member there is no CLAIMANT-role actor to answer as.
      if (action == ClaimAction.ANSWER_INFO && run.u(run.w.holder()).role() != Role.CLAIMANT) {
        expected--;
        continue;
      }
      Map<String, Object> before = run.state();
      String ifMatch = ETags.of(run.version());
      BigDecimal amount = BigDecimal.ONE.setScale(Money.scaleOf(run.w.currency()));
      String code = null;
      try {
        switch (action) {
          case PICK_UP -> staff.pickUp(officer, run.id, ifMatch);
          case ASSIGN -> staff.assign(run.actor(ActorKind.MANAGER), run.id, ifMatch, run.target(TargetKind.A));
          case ADD_NOTE -> staff.note(officer, run.id, ifMatch, "note");
          case REQUEST_INFO -> staff.requestInfo(officer, run.id, ifMatch, "please send photos");
          case ANSWER_INFO -> claimant.answerInfo(run.actor(ActorKind.CLAIMANT), run.id, ifMatch, "here are the photos");
          case ASSESS -> staff.assess(officer, run.id, ifMatch, amount);
          case APPROVE -> staff.approve(run.actor(ActorKind.MANAGER), run.id, ifMatch, amount);
          case REJECT -> staff.reject(officer, run.id, ifMatch, RejectReason.values()[0], "Explained to claimant.");
          case SETTLE -> staff.settle(officer, run.id, ifMatch, amount);
        }
      } catch (DomainException e) {
        code = e.code().name();
      } catch (ApiException e) {
        code = e.code();
      }
      assertThat(code).as("%s on a %s claim", action, run.status()).isEqualTo("ILLEGAL_TRANSITION");
      assertThat(run.state()).as("%s on a terminal claim changes nothing (version, status, ledger, activity, outbox)",
          action).isEqualTo(before);
      probed++;
    }
    assertThat(probed).isEqualTo(expected);
    return probed;
  }

  /**
   * Mostly a command that is authorised and legal for the claim's current status (read from SQL), so the
   * sequences get past SUBMITTED and reach APPROVED, SETTLED and REJECTED. The rest come from the blind
   * generator of {@link ClaimInvariantsPropertyTest}, which keeps the illegal, unauthorised and
   * ill-formed commands in the run.
   */
  static Step nextStep(Run run, Random r) {
    if (r.nextInt(100) >= LEGAL_PERCENT) return ClaimInvariantsPropertyTest.randomStep(r);
    ClaimStatus st = ClaimStatus.valueOf(run.status());
    int pct = 10 + r.nextInt(81);
    RejectReason reason = RejectReason.values()[r.nextInt(RejectReason.values().length)];
    return switch (st) {
      case SUBMITTED -> legal(ClaimAction.PICK_UP, ActorKind.A, pct, reason);
      case UNDER_REVIEW -> switch (r.nextInt(10)) {
        case 0, 1, 2, 3 -> legal(ClaimAction.ASSESS, ActorKind.ASSIGNED, pct, reason);
        case 4, 5 -> legal(ClaimAction.REQUEST_INFO, ActorKind.ASSIGNED, pct, reason);
        case 6 -> legal(ClaimAction.ADD_NOTE, ActorKind.ASSIGNED, pct, reason);
        case 7 -> new Step(ClaimAction.ASSIGN, ActorKind.MANAGER, AmountKind.VALID, pct, TargetKind.A, reason, true);
        default -> legal(ClaimAction.REJECT, ActorKind.ASSIGNED, pct, reason);
      };
      case AWAITING_INFO -> r.nextInt(8) == 0
          ? legal(ClaimAction.ADD_NOTE, ActorKind.ASSIGNED, pct, reason)
          : legal(ClaimAction.ANSWER_INFO, ActorKind.CLAIMANT, pct, reason);
      case ASSESSED -> switch (r.nextInt(10)) {
        case 0 -> legal(ClaimAction.REQUEST_INFO, ActorKind.ASSIGNED, pct, reason);
        case 1 -> legal(ClaimAction.REJECT, ActorKind.MANAGER, pct, reason);
        case 2 -> legal(ClaimAction.ASSESS, ActorKind.ASSIGNED, pct, reason);
        default -> new Step(ClaimAction.APPROVE, ActorKind.MANAGER, AmountKind.VALID, pct, TargetKind.A, reason, true);
      };
      case APPROVED -> r.nextInt(8) == 0
          ? legal(ClaimAction.ADD_NOTE, ActorKind.ASSIGNED, pct, reason)
          : new Step(ClaimAction.SETTLE, ActorKind.MANAGER, AmountKind.MATCH_APPROVED, pct, TargetKind.A, reason, true);
      case SETTLED, REJECTED -> ClaimInvariantsPropertyTest.randomStep(r); // nothing is legal here
    };
  }

  private static Step legal(ClaimAction action, ActorKind actor, int pct, RejectReason reason) {
    return new Step(action, actor, AmountKind.VALID, pct, TargetKind.A, reason, true);
  }

  @Test
  void invariantsHoldThroughTheRealServicesAndAreVisibleInSql() {
    Map<ClaimStatus, Integer> distribution = new EnumMap<>(ClaimStatus.class);
    Map<ClaimStatus, Integer> reached = new EnumMap<>(ClaimStatus.class); // sequences that ever visited a status
    Map<String, Integer> codes = new TreeMap<>();
    int succeeded = 0;
    int failed = 0;
    int terminalClaims = 0;
    int terminalProbes = 0;
    List<UUID> created = new ArrayList<>();
    for (int i = 0; i < SEQUENCES; i++) {
      long seed = ClaimInvariantsPropertyTest.BASE_SEED + i;
      Random r = new Random(seed);
      World w = WORLDS.get(r.nextInt(WORLDS.size()));
      int estimatePct = r.nextInt(151);
      BigDecimal estimate = w.coverage().multiply(BigDecimal.valueOf(estimatePct))
          .divide(BigDecimal.valueOf(100), Money.scaleOf(w.currency()), RoundingMode.DOWN);
      int length = 5 + r.nextInt(56);
      Run run = new Run(w, submit(w, estimate));
      created.add(run.id);
      List<String> trail = new ArrayList<>();
      try {
        for (int n = 0; n < length; n++) {
          // Once the claim is closed nothing can succeed: try each action once, then end the sequence.
          if (ClaimStatus.valueOf(run.status()).isTerminal()) {
            terminalClaims++;
            terminalProbes += probeTerminal(run);
            break;
          }
          Step s = nextStep(run, r);
          // one command in twelve carries a stale or a missing If-Match
          int roll = r.nextInt(24);
          int mode = roll == 0 ? 1 : roll == 1 ? 2 : 0;
          trail.add(s + (mode == 0 ? "" : mode == 1 ? " [stale]" : " [no If-Match]"));
          run.attempt(s, mode);
        }
        run.assertInvariants();
      } catch (Throwable t) {
        throw new AssertionError("DB invariant failed. seed=" + seed + " world=" + w.name() + " estimate="
            + estimate + " claim=" + run.id + " commands so far=" + trail + " cause: " + t.getMessage(), t);
      }
      distribution.merge(ClaimStatus.valueOf(run.status()), 1, Integer::sum);
      jdbc.queryForList("select distinct to_status from claim_activity where claim_id = ?", String.class, run.id)
          .forEach(st -> reached.merge(ClaimStatus.valueOf(st), 1, Integer::sum));
      succeeded += run.succeeded;
      failed += run.failed;
      run.failureCodes.forEach((k, v) -> codes.merge(k, v, Integer::sum));
    }
    System.out.println("DB random-sequence final status distribution (" + SEQUENCES + " sequences, baseSeed="
        + ClaimInvariantsPropertyTest.BASE_SEED + "): " + distribution);
    System.out.println("DB random-sequence sequences that ever reached each status: " + reached);
    System.out.println("DB random-sequence commands: " + succeeded + " succeeded, " + failed + " failed; failures by code: "
        + codes);

    System.out.println("DB terminal probes: " + terminalProbes + " refused actions over " + terminalClaims
        + " terminal claims (" + ACTIONS + " each)");
    assertThat(terminalClaims).as("claims that reached a terminal state").isGreaterThanOrEqualTo(floor(FLOOR_SETTLED + FLOOR_REJECTED));
    assertThat(terminalProbes).as("every action probed on every terminal claim (8 where the holder is staff)")
        .isBetween(terminalClaims * (ACTIONS - 1), terminalClaims * ACTIONS);
    assertThat(terminalProbes).as("floor on terminal probes").isGreaterThanOrEqualTo((ACTIONS - 1) * floor(FLOOR_SETTLED + FLOOR_REJECTED));
    assertFinalLedgerCheck(created);

    // Floors are scaled to the sequence count; see FLOOR_* below.
    int total = succeeded + failed;
    System.out.printf("DB random-sequence success rate: %d of %d = %.1f%%%n", succeeded, total, 100.0 * succeeded / total);
    assertThat(succeeded * 100 / total).as("at least %d%% of commands are authorised and legal", MIN_SUCCESS_PERCENT)
        .isGreaterThanOrEqualTo(MIN_SUCCESS_PERCENT);
    // the terminal probes are counted apart; these are the blind-generator failures (83 at 40 sequences)
    assertThat(failed).as("illegal commands are still exercised").isGreaterThan(SEQUENCES);
    assertThat(codes.keySet()).as("failure kinds reached").contains("ILLEGAL_TRANSITION", "NOT_FOUND",
        "STALE_VERSION", "PRECONDITION_REQUIRED");
    assertThat(distribution.getOrDefault(ClaimStatus.SETTLED, 0)).as("SETTLED as final status")
        .isGreaterThanOrEqualTo(floor(FLOOR_SETTLED));
    assertThat(distribution.getOrDefault(ClaimStatus.REJECTED, 0)).as("REJECTED as final status")
        .isGreaterThanOrEqualTo(floor(FLOOR_REJECTED));
    assertThat(reached.getOrDefault(ClaimStatus.AWAITING_INFO, 0)).as("sequences that visited AWAITING_INFO")
        .isGreaterThanOrEqualTo(floor(FLOOR_AWAITING));
    assertThat(reached.getOrDefault(ClaimStatus.ASSESSED, 0)).as("sequences that visited ASSESSED")
        .isGreaterThanOrEqualTo(floor(FLOOR_ASSESSED));
    assertThat(reached.getOrDefault(ClaimStatus.APPROVED, 0)).as("sequences that visited APPROVED")
        .isGreaterThanOrEqualTo(floor(FLOOR_APPROVED));
  }

  // Per 1000 sequences, about half of what the default seeds give at 40 sequences: final SETTLED 23,
  // final REJECTED 14; sequences that visited AWAITING_INFO 7, ASSESSED 27, APPROVED 24. Success rate 45.8%
  // against a floor of 40%. Before the state-aware generator: 113 of 1,260 (9%), SETTLED 2, REJECTED 5.
  static final int MIN_SUCCESS_PERCENT = 40;
  static final int FLOOR_SETTLED = 275;
  static final int FLOOR_REJECTED = 175;
  static final int FLOOR_AWAITING = 75;
  static final int FLOOR_ASSESSED = 325;
  static final int FLOOR_APPROVED = 300;

  /** D3, final: over every ledger row of every claim this test created. */
  void assertFinalLedgerCheck(List<UUID> claims) {
    Object[] ids = claims.toArray();
    String in = String.join(",", java.util.Collections.nCopies(ids.length, "?"));
    assertThat(jdbc.queryForObject("select count(*) from financial_entry where claim_id in (" + in + ")", Long.class, ids))
        .as("the ledger has rows for the created claims").isPositive();
    assertThat(jdbc.queryForObject("select count(*) from financial_entry where amount < 0 and claim_id in (" + in + ")",
        Long.class, ids)).as("no negative ledger amount").isZero();
    assertThat(jdbc.queryForObject("select count(*) from financial_entry f join claim c on c.id = f.claim_id "
        + "where f.currency <> c.currency and c.id in (" + in + ")", Long.class, ids)).as("ledger currency").isZero();
    assertThat(jdbc.queryForObject("select count(*) from claim c where c.id in (" + in + ") and ("
        + "c.reserve <> coalesce((select amount from financial_entry e where e.claim_id = c.id and e.entry_type = 'RESERVE_SET' order by e.id desc limit 1), 0) "
        + "or c.paid <> coalesce((select sum(amount) from financial_entry e where e.claim_id = c.id and e.entry_type = 'PAYMENT'), 0) "
        + "or c.reserve - c.paid < 0 "
        + "or coalesce((select amount from financial_entry e where e.claim_id = c.id and e.entry_type = 'RESERVE_SET' order by e.id desc limit 1), 0) > c.coverage_limit)",
        Long.class, ids)).as("every claim agrees with its ledger").isZero();
    assertThat(jdbc.queryForObject("select count(*) from (select claim_id from info_request where answered_at is null "
        + "and claim_id in (" + in + ") group by claim_id having count(*) > 1) x", Long.class, ids)).as("open info_request <= 1").isZero();
  }

  static int floor(int per1000) {
    return per1000 * SEQUENCES / 1000;
  }
}
