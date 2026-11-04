package com.chubb.claims.claim.domain;

import java.util.UUID;

public final class ConflictCheck {
  private ConflictCheck() {}

  public static void check(UUID actorId, UUID policyholderId) {
    if (actorId.equals(policyholderId)) {
      throw new DomainException(ErrorCode.CONFLICT_OF_INTEREST, "Staff cannot act on their own policy");
    }
  }
}
