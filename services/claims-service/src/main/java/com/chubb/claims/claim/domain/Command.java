package com.chubb.claims.claim.domain;

import java.math.BigDecimal;

/** One staff or claimant command. Unused fields are null. */
public record Command(
    ClaimAction action, Actor actor, BigDecimal amount, TargetUser target, RejectReason reason, String text) {

  public static Command pickUp(Actor a) {
    return new Command(ClaimAction.PICK_UP, a, null, null, null, null);
  }

  public static Command assign(Actor a, TargetUser target) {
    return new Command(ClaimAction.ASSIGN, a, null, target, null, null);
  }

  public static Command note(Actor a, String text) {
    return new Command(ClaimAction.ADD_NOTE, a, null, null, null, text);
  }

  public static Command requestInfo(Actor a, String question) {
    return new Command(ClaimAction.REQUEST_INFO, a, null, null, null, question);
  }

  public static Command answerInfo(Actor a, String answer) {
    return new Command(ClaimAction.ANSWER_INFO, a, null, null, null, answer);
  }

  public static Command assess(Actor a, BigDecimal reserve) {
    return new Command(ClaimAction.ASSESS, a, reserve, null, null, null);
  }

  public static Command approve(Actor a, BigDecimal amount) {
    return new Command(ClaimAction.APPROVE, a, amount, null, null, null);
  }

  public static Command reject(Actor a, RejectReason reason, String explanation) {
    return new Command(ClaimAction.REJECT, a, null, null, reason, explanation);
  }

  public static Command settle(Actor a, BigDecimal paid) {
    return new Command(ClaimAction.SETTLE, a, paid, null, null, null);
  }
}
