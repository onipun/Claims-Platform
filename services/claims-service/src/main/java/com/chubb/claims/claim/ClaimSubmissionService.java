package com.chubb.claims.claim;

import com.chubb.claims.claim.api.ClaimantClaimDetail;
import com.chubb.claims.claim.api.SubmitClaimRequest;
import com.chubb.claims.identity.AuthenticatedUser;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Idempotent submission (trap 7): the same key with the same body returns the original; the same key
 * with a different body is 422 IDEMPOTENCY_KEY_REUSED. Deliberately NOT transactional: when two requests with the same
 * key race, the loser's insert fails on the unique index and its transaction is poisoned, so the
 * original is read in a fresh transaction after the failure.
 */
@Service
public class ClaimSubmissionService {
  public record Result(ClaimantClaimDetail claim, boolean created) {}

  private final ClaimSubmissionTx tx;

  ClaimSubmissionService(ClaimSubmissionTx tx) {
    this.tx = tx;
  }

  public Result submit(AuthenticatedUser user, String idempotencyKey, SubmitClaimRequest request) {
    String hash = RequestHash.of(request);
    Optional<ClaimantClaimDetail> existing = tx.findExisting(user.id(), idempotencyKey, hash);
    if (existing.isPresent()) return new Result(existing.get(), false);
    try {
      return new Result(tx.create(user, idempotencyKey, hash, request), true);
    } catch (DataIntegrityViolationException race) {
      return tx.findExisting(user.id(), idempotencyKey, hash)
          .map(c -> new Result(c, false))
          .orElseThrow(() -> race); // some other constraint: a real error
    }
  }
}
