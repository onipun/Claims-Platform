package com.chubb.claims.claim.domain;

import com.chubb.claims.claim.domain.Decision.InfoChange;
import com.chubb.claims.claim.domain.Decision.Visibility;
import java.math.BigDecimal;

/**
 * The single place where a command is checked against the rules. LIFECYCLE.md order: access (404/403,
 * {@link #checkAccess}, which callers run before If-Match), the transition table (409), the action's
 * preconditions, amounts (422), then authority (403).
 */
public final class ClaimDecider {
  private ClaimDecider() {}

  /** Step 2: may this actor touch this claim at all. Throws 404 or 403 CONFLICT_OF_INTEREST. */
  public static void checkAccess(ClaimSnapshot s, Command c) {
    throwUnlessAllowed(ActorPermissions.access(c.actor(), s.context(), c.action()));
  }

  public static Decision decide(ClaimSnapshot s, Command c) {
    checkAccess(s, c);
    ClaimStatus next = Transitions.require(s.status(), c.action());
    throwUnlessAllowed(ActorPermissions.preconditions(s.context(), c.action(), c.target()));

    String cur = s.currency();
    return switch (c.action()) {
      case PICK_UP -> new Decision(c.action(), next, c.actor().id(), s.approvedAmount(), null, null,
          InfoChange.NONE, Visibility.INTERNAL, "Picked up", null, null);
      case ASSIGN -> new Decision(c.action(), next, c.target().id(), s.approvedAmount(), null, null,
          InfoChange.NONE, Visibility.INTERNAL, "Assigned", null, null);
      case ADD_NOTE -> text(s, c, next, InfoChange.NONE, Visibility.INTERNAL);
      case REQUEST_INFO -> text(s, c, next, InfoChange.OPEN, Visibility.CLAIMANT);
      case ANSWER_INFO -> text(s, c, next, InfoChange.ANSWER, Visibility.CLAIMANT);
      case ASSESS -> {
        AmountRules.assessReserve(c.amount(), s.coverageLimit(), cur);
        BigDecimal reserve = Money.normalize(c.amount(), cur);
        yield money(s, c, next, s.approvedAmount(), new LedgerEntry(LedgerType.RESERVE_SET, reserve), "Assessed",
            Visibility.INTERNAL);
      }
      case APPROVE -> {
        AmountRules.approve(c.amount(), s.coverageLimit(), cur);
        AuthorityCheck.check(c.actor(), s.market(), c.amount());
        BigDecimal approved = Money.normalize(c.amount(), cur);
        yield money(s, c, next, approved, new LedgerEntry(LedgerType.RESERVE_SET, approved), "Approved",
            Visibility.CLAIMANT);
      }
      case REJECT -> {
        RejectReason.requireValid(c.reason(), c.text());
        yield new Decision(c.action(), next, s.assignedOfficerId(), s.approvedAmount(), ReserveStatus.ASSESSED,
            new LedgerEntry(LedgerType.RESERVE_SET, Money.normalize(BigDecimal.ZERO, cur)),
            InfoChange.NONE, Visibility.CLAIMANT, c.text(), c.reason(), c.text());
      }
      case SETTLE -> {
        AmountRules.settle(c.amount(), s.approvedAmount(), cur);
        yield money(s, c, next, s.approvedAmount(),
            new LedgerEntry(LedgerType.PAYMENT, Money.normalize(c.amount(), cur)), "Settled", Visibility.CLAIMANT);
      }
    };
  }

  private static void throwUnlessAllowed(PermissionResult r) {
    switch (r) {
      case PermissionResult.Allowed a -> { }
      case PermissionResult.NotFound n -> throw new DomainException(ErrorCode.NOT_FOUND, "Claim not found");
      case PermissionResult.Forbidden f -> throw new DomainException(f.code(), f.code().name());
      case PermissionResult.Rejected x -> throw new DomainException(x.code(), x.code().name());
    }
  }

  private static Decision text(ClaimSnapshot s, Command c, ClaimStatus next, InfoChange info, Visibility v) {
    if (c.text() == null || c.text().isBlank()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Text is required");
    }
    return new Decision(c.action(), next, s.assignedOfficerId(), s.approvedAmount(), null, null, info, v,
        c.text(), null, null);
  }

  private static Decision money(ClaimSnapshot s, Command c, ClaimStatus next, BigDecimal approved,
      LedgerEntry entry, String message, Visibility visibility) {
    return new Decision(c.action(), next, s.assignedOfficerId(), approved, ReserveStatus.ASSESSED, entry,
        InfoChange.NONE, visibility, message, null, null);
  }
}
