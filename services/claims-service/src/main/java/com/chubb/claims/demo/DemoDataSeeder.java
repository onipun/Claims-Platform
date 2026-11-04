package com.chubb.claims.demo;

import com.chubb.claims.claim.ClaimSubmissionService;
import com.chubb.claims.claim.ClaimantClaimService;
import com.chubb.claims.claim.ETags;
import com.chubb.claims.claim.StaffClaimService;
import com.chubb.claims.claim.api.MotorDetails;
import com.chubb.claims.claim.api.PropertyDetails;
import com.chubb.claims.claim.api.SubmitClaimRequest;
import com.chubb.claims.claim.domain.ClaimType;
import com.chubb.claims.claim.domain.Line;
import com.chubb.claims.claim.domain.RejectReason;
import com.chubb.claims.config.OffsetClock;
import com.chubb.claims.identity.AuthenticatedUser;
import com.chubb.claims.reference.AppUser;
import com.chubb.claims.reference.AppUserRepository;
import com.chubb.claims.reference.Market;
import com.chubb.claims.reference.MarketRepository;
import com.chubb.claims.reference.Policy;
import com.chubb.claims.reference.PolicyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Demo profile only. Creates about 25 claims through the REAL services, acting as the seeded users, so
 * every claim has its timeline, ledger entries and outbox events (trap 5). Idempotent: does nothing if
 * any claim exists.
 *
 * <p>Back-dating: the services take their time from the application {@link OffsetClock}. Before each
 * command the seeder moves that clock to the intended moment in the past ({@code shiftTo}) and resets it
 * afterwards. No service code knows about this, and pick-up/decision due times fall out of the real SLA
 * configuration, so overdue and ageing are real computations on real timestamps.
 */
@Component
@Profile("demo")
class DemoDataSeeder implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

  private final ClaimSubmissionService submission;
  private final ClaimantClaimService claimant;
  private final StaffClaimService staff;
  private final AppUserRepository users;
  private final PolicyRepository policies;
  private final MarketRepository markets;
  private final JdbcTemplate jdbc;
  private final OffsetClock clock;

  DemoDataSeeder(ClaimSubmissionService submission, ClaimantClaimService claimant, StaffClaimService staff,
      AppUserRepository users, PolicyRepository policies, MarketRepository markets, JdbcTemplate jdbc,
      OffsetClock clock) {
    this.submission = submission;
    this.claimant = claimant;
    this.staff = staff;
    this.users = users;
    this.policies = policies;
    this.markets = markets;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public void run(ApplicationArguments args) {
    Integer existing = jdbc.queryForObject("select count(*) from claim", Integer.class);
    if (existing != null && existing > 0) {
      log.info("Demo seeder: {} claims already exist, nothing to do", existing);
      return;
    }
    seed();
    Map<String, Integer> byStatus = new java.util.TreeMap<>();
    jdbc.query("select status, count(*) c from claim group by status",
        rs -> { byStatus.put(rs.getString(1), rs.getInt(2)); });
    Map<String, Integer> byMarket = new java.util.TreeMap<>();
    jdbc.query("select market_code, count(*) c from claim group by market_code",
        rs -> { byMarket.put(rs.getString(1), rs.getInt(2)); });
    Integer outbox = jdbc.queryForObject("select count(*) from outbox_event", Integer.class);
    log.info("Demo seeder: created {} claims. By status {}; by market {}; outbox events {}",
        byStatus.values().stream().mapToInt(Integer::intValue).sum(), byStatus, byMarket, outbox);
  }

  // ---- the data ----

  private void seed() {
    // SG (13) - SGD
    submit("claimant.sg", "MOT-SG-100001", ClaimType.OWN_DAMAGE, "4200", hours(30)); // pickup overdue
    submit("claimant.sg", "MOT-SG-100001", ClaimType.WINDSCREEN, "650", hours(2));
    submit("claimant2.sg", "PRP-SG-100004", ClaimType.WATER_DAMAGE, "7800", days(5)); // pickup long overdue
    // Late-reported SG motor: incident 6 days before the report.
    final Claim c1 = submitLate("claimant.sg", "MOT-SG-100001", ClaimType.THIRD_PARTY_DAMAGE, "9100", days(1), 6,
        "Away overseas until the weekend");
    at(c1, 5, () -> staff.pickUp(u("officer.sg"), c1.id, c1.etag()));

    final Claim c2 = submit("claimant.sg", "MOT-SG-100001", ClaimType.OWN_DAMAGE, "3300", days(3));
    at(c2, 4, () -> staff.pickUp(u("officer.sg"), c2.id, c2.etag()));

    final Claim c3 = submit("claimant2.sg", "PRP-SG-100004", ClaimType.FIRE, "42000", days(10));
    at(c3, 6, () -> staff.assign(u("manager.sg"), c3.id, c3.etag(), id("officer2.sg")));
    at(c3, 30, () -> staff.note(u("officer2.sg"), c3.id, c3.etag(), "Site visit booked with the loss adjuster"));

    final Claim c4 = submit("claimant.sg", "MOT-SG-100001", ClaimType.THEFT, "18000", days(6));
    at(c4, 3, () -> staff.pickUp(u("officer.sg"), c4.id, c4.etag()));
    at(c4, 20, () -> staff.requestInfo(u("officer.sg"), c4.id, c4.etag(), "Please send the police report number"));

    final Claim c5 = submit("claimant2.sg", "MOT-SG-100003", ClaimType.OWN_DAMAGE, "5600", days(7));
    at(c5, 2, () -> staff.pickUp(u("officer2.sg"), c5.id, c5.etag()));
    at(c5, 26, () -> staff.requestInfo(u("officer2.sg"), c5.id, c5.etag(), "Which workshop will repair the car?"));
    at(c5, 50, () -> claimant.answerInfo(u("claimant2.sg"), c5.id, c5.etag(), "Ah Seng Auto, Ubi"));

    final Claim c6 = submit("claimant.sg", "PRP-SG-100002", ClaimType.STORM, "15500", days(18)); // decision overdue
    at(c6, 5, () -> staff.pickUp(u("officer2.sg"), c6.id, c6.etag()));
    at(c6, 90, () -> staff.assess(u("officer2.sg"), c6.id, c6.etag(), bd("14200")));

    final Claim c7 = submit("claimant.sg", "MOT-SG-100001", ClaimType.OWN_DAMAGE, "7400", days(4));
    at(c7, 3, () -> staff.pickUp(u("officer.sg"), c7.id, c7.etag()));
    at(c7, 40, () -> staff.assess(u("officer.sg"), c7.id, c7.etag(), bd("6900")));

    final Claim c8 = submit("claimant2.sg", "MOT-SG-100003", ClaimType.TOTAL_LOSS, "38000", days(9));
    at(c8, 4, () -> staff.pickUp(u("officer2.sg"), c8.id, c8.etag()));
    at(c8, 60, () -> staff.assess(u("officer2.sg"), c8.id, c8.etag(), bd("36500")));
    at(c8, 100, () -> staff.approve(u("officer2.sg"), c8.id, c8.etag(), bd("35000")));

    final Claim c9 = submit("claimant.sg", "MOT-SG-100001", ClaimType.OWN_DAMAGE, "2900", days(25));
    at(c9, 3, () -> staff.pickUp(u("officer.sg"), c9.id, c9.etag()));
    at(c9, 48, () -> staff.assess(u("officer.sg"), c9.id, c9.etag(), bd("2700")));
    at(c9, 120, () -> staff.approve(u("officer.sg"), c9.id, c9.etag(), bd("2600")));
    at(c9, 240, () -> staff.settle(u("officer.sg"), c9.id, c9.etag(), bd("2600")));

    final Claim c10 = submit("claimant.sg", "PRP-SG-100002", ClaimType.OTHER, "5200", days(12));
    at(c10, 6, () -> staff.pickUp(u("officer.sg"), c10.id, c10.etag()));
    at(c10, 100, () -> staff.reject(u("officer.sg"), c10.id, c10.etag(), RejectReason.INSUFFICIENT_EVIDENCE,
        "No supporting documents were provided"));

    // HK (6) - HKD
    submit("claimant.hk", "MOT-HK-200001", ClaimType.OWN_DAMAGE, "28000", days(1));

    final Claim c11 = submit("claimant.hk", "PRP-HK-200002", ClaimType.WATER_DAMAGE, "95000", days(5));
    at(c11, 8, () -> staff.pickUp(u("officer.hk"), c11.id, c11.etag()));

    final Claim c12 = submit("claimant.hk", "MOT-HK-200001", ClaimType.THIRD_PARTY_DAMAGE, "61000", days(8));
    at(c12, 5, () -> staff.pickUp(u("officer.hk"), c12.id, c12.etag()));
    at(c12, 70, () -> staff.assess(u("officer.hk"), c12.id, c12.etag(), bd("58000")));

    final Claim c13 = submit("claimant.hk", "PRP-HK-200002", ClaimType.FIRE, "310000", days(3));
    at(c13, 4, () -> staff.pickUp(u("officer.hk"), c13.id, c13.etag()));
    at(c13, 30, () -> staff.assess(u("officer.hk"), c13.id, c13.etag(), bd("295000")));
    at(c13, 50, () -> staff.approve(u("officer.hk"), c13.id, c13.etag(), bd("90000")));

    final Claim c14 = submit("claimant.hk", "MOT-HK-200001", ClaimType.TOTAL_LOSS, "260000", days(20));
    at(c14, 6, () -> staff.pickUp(u("officer.hk"), c14.id, c14.etag()));
    at(c14, 80, () -> staff.assess(u("officer.hk"), c14.id, c14.etag(), bd("250000")));
    at(c14, 150, () -> staff.approve(u("manager.apac"), c14.id, c14.etag(), bd("240000")));
    at(c14, 300, () -> staff.settle(u("manager.apac"), c14.id, c14.etag(), bd("240000")));

    final Claim c15 = submit("claimant.hk", "MOT-HK-200001", ClaimType.THEFT, "45000", days(6));
    at(c15, 7, () -> staff.pickUp(u("officer.hk"), c15.id, c15.etag()));
    at(c15, 60, () -> staff.reject(u("officer.hk"), c15.id, c15.etag(), RejectReason.NOT_COVERED,
        "Theft cover does not apply to this policy"));

    // MY (3) - MYR
    submit("claimant.my", "MOT-MY-300001", ClaimType.WINDSCREEN, "900", days(2));

    final Claim c16 = submit("claimant.my", "PRP-MY-300002", ClaimType.STORM, "22000", days(4));
    at(c16, 5, () -> staff.pickUp(u("officer.my"), c16.id, c16.etag()));
    at(c16, 30, () -> staff.requestInfo(u("officer.my"), c16.id, c16.etag(), "Please upload photos of the damaged roof"));

    final Claim c17 = submit("claimant.my", "MOT-MY-300001", ClaimType.OWN_DAMAGE, "6400", days(15));
    at(c17, 4, () -> staff.pickUp(u("officer.my"), c17.id, c17.etag()));
    at(c17, 50, () -> staff.assess(u("officer.my"), c17.id, c17.etag(), bd("6100")));
    at(c17, 90, () -> staff.approve(u("officer.my"), c17.id, c17.etag(), bd("5900")));
    at(c17, 200, () -> staff.settle(u("officer.my"), c17.id, c17.etag(), bd("5900")));

    // ID (3) - IDR, no minor unit
    final Claim c18 = submit("claimant.id", "MOT-ID-400001", ClaimType.OWN_DAMAGE, "15500000", days(2));
    at(c18, 6, () -> staff.pickUp(u("officer.id"), c18.id, c18.etag()));

    final Claim c19 = submit("claimant.id", "PRP-ID-400002", ClaimType.WATER_DAMAGE, "85000000", days(5));
    at(c19, 4, () -> staff.pickUp(u("officer.id"), c19.id, c19.etag()));
    at(c19, 40, () -> staff.assess(u("officer.id"), c19.id, c19.etag(), bd("80000000")));
    at(c19, 80, () -> staff.approve(u("officer.id"), c19.id, c19.etag(), bd("78000000")));

    final Claim c20 = submit("claimant.id", "MOT-ID-400001", ClaimType.THIRD_PARTY_DAMAGE, "32000000", days(11));
    at(c20, 3, () -> staff.pickUp(u("officer.id"), c20.id, c20.etag()));
    at(c20, 70, () -> staff.assess(u("officer.id"), c20.id, c20.etag(), bd("30000000")));
  }

  // ---- mechanics ----

  /** A claim being driven; etag() reads the current version, as a client reloading before it acts. */
  private final class Claim {
    final UUID id;
    final Instant submittedAt;

    Claim(UUID id, Instant submittedAt) {
      this.id = id;
      this.submittedAt = submittedAt;
    }

    String etag() {
      return ETags.of(jdbc.queryForObject("select version from claim where id = ?", Long.class, id));
    }
  }

  private static java.time.Duration hours(long h) { return java.time.Duration.ofHours(h); }

  private static java.time.Duration days(long d) { return java.time.Duration.ofDays(d); }

  private Claim submit(String claimantEmail, String policyNumber, ClaimType type, String estimate,
      java.time.Duration ago) {
    return submitLate(claimantEmail, policyNumber, type, estimate, ago, 0, null);
  }

  /** @param incidentDaysBefore days between the incident and the report; 6 makes an SG motor claim late */
  private Claim submitLate(String claimantEmail, String policyNumber, ClaimType type, String estimate,
      java.time.Duration ago, int incidentDaysBefore, String lateReason) {
    Policy policy = policies.findAll().stream().filter(p -> p.getPolicyNumber().equals(policyNumber))
        .findFirst().orElseThrow();
    Market market = markets.findById(policy.getMarketCode()).orElseThrow();
    Instant when = Instant.now().minus(ago);
    ZoneId zone = market.zone();
    LocalDateTime incident = LocalDateTime.ofInstant(when, zone).minusHours(3).minusDays(incidentDaysBefore)
        .truncatedTo(ChronoUnit.MINUTES);
    boolean motor = policy.getLine() == Line.MOTOR;
    SubmitClaimRequest req = new SubmitClaimRequest(policy.getId(), policy.getLine(), type, incident,
        bd(estimate), "+6500000000", motor ? "Collision at a junction" : "Damage to the insured premises",
        lateReason, motor ? new MotorDetails("SGX1234A", "Demo Driver", null, false, "PR-DEMO", null, "Junction of Orchard Road and Bras Basah Road") : null,
        motor ? null : new PropertyDetails("1 Demo Street", "Water leak from the unit above", "OWNER"));
    AuthenticatedUser who = u(claimantEmail);
    ClaimSubmissionService.Result r = shifted(when,
        () -> submission.submit(who, "demo-seed-" + UUID.randomUUID(), req));
    return new Claim(r.claim().id(), when);
  }

  private void at(Claim c, long hoursAfterSubmit, Runnable action) {
    Instant when = c.submittedAt.plus(hoursAfterSubmit, ChronoUnit.HOURS);
    Instant latest = Instant.now().minusSeconds(60);
    shifted(when.isAfter(latest) ? latest : when, () -> { action.run(); return null; });
  }

  <T> T shifted(Instant when, Supplier<T> action) { // package-private for the test
    clock.shiftTo(when);
    try {
      return action.get();
    } finally {
      clock.reset();
    }
  }

  private AuthenticatedUser u(String shortEmail) {
    AppUser user = users.findByEmail(shortEmail + "@demo.test").orElseThrow();
    return new AuthenticatedUser(user.getId(), user.getRole(), user.getMarkets(), user.getName());
  }

  private UUID id(String shortEmail) {
    return users.findByEmail(shortEmail + "@demo.test").orElseThrow().getId();
  }

  private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
