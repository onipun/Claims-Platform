package com.chubb.claims.claim.domain;

/**
 * Who may act, in LIFECYCLE.md order. {@link #access} is step 2 (404 / 403 conflict of interest) and
 * runs before If-Match; {@link #preconditions} is step 5 and runs after the transition table.
 * Authority is checked last, against the amount.
 */
public final class ActorPermissions {
  private ActorPermissions() {}

  /** Access and conflict of interest only. Nothing here depends on the claim's status. */
  public static PermissionResult access(Actor actor, ClaimContext claim, ClaimAction action) {
    if (action == ClaimAction.ANSWER_INFO) {
      return actor.role() == Role.CLAIMANT && actor.id().equals(claim.policyholderId())
          ? new PermissionResult.Allowed()
          : new PermissionResult.NotFound();
    }

    if (actor.role() == Role.CLAIMANT || !actor.markets().contains(claim.market())) {
      return new PermissionResult.NotFound();
    }
    boolean allowedByRole = switch (action) {
      case PICK_UP -> true;
      case ASSIGN -> actor.role() == Role.MANAGER;
      default -> actor.role() == Role.MANAGER || actor.id().equals(claim.assignedOfficerId());
    };
    if (!allowedByRole) return new PermissionResult.NotFound();

    if (actor.id().equals(claim.policyholderId())) {
      return new PermissionResult.Forbidden(ErrorCode.CONFLICT_OF_INTEREST);
    }
    return new PermissionResult.Allowed();
  }

  /** The action's own preconditions, once the transition is known to be legal. */
  public static PermissionResult preconditions(ClaimContext claim, ClaimAction action, TargetUser target) {
    if (action == ClaimAction.ASSIGN) {
      if (target == null || target.role() == Role.CLAIMANT || !target.markets().contains(claim.market())) {
        return new PermissionResult.Rejected(ErrorCode.INVALID_ASSIGNEE);
      }
      if (target.id().equals(claim.policyholderId())) {
        return new PermissionResult.Forbidden(ErrorCode.CONFLICT_OF_INTEREST);
      }
    }
    return new PermissionResult.Allowed();
  }

  /** Access, then preconditions. For callers that have no transition to check (allowed-actions, documents). */
  public static PermissionResult check(Actor actor, ClaimContext claim, ClaimAction action, TargetUser target) {
    PermissionResult a = access(actor, claim, action);
    return a instanceof PermissionResult.Allowed ? preconditions(claim, action, target) : a;
  }
}
