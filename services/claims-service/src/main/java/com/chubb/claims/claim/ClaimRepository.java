package com.chubb.claims.claim;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ClaimRepository extends JpaRepository<Claim, UUID>, JpaSpecificationExecutor<Claim> {
  /** The staff market scope (trap 6, T10): a claim outside the caller's markets is simply not found. */
  Optional<Claim> findByIdAndMarketCodeIn(UUID id, Collection<String> markets);

  /** The ownership check (trap 6, T8): another claimant's claim is simply not found. */
  Optional<Claim> findByIdAndClaimantId(UUID id, UUID claimantId);

  Optional<Claim> findByClaimantIdAndIdempotencyKey(UUID claimantId, String idempotencyKey);

  Page<Claim> findByClaimantId(UUID claimantId, Pageable pageable);

  /**
   * Document writes (R5, R6) take the claim row FOR UPDATE so uploads and reviews serialise per claim and
   * against commands: the status and cap checks then see committed state. A command only waits on the
   * lock at its UPDATE, and a document write never changes the claim row, so the order is always
   * claim row first, then document and activity rows: no cycle.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Claim c where c.id = :id and c.claimantId = :claimantId")
  Optional<Claim> lockByIdAndClaimantId(@Param("id") UUID id, @Param("claimantId") UUID claimantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Claim c where c.id = :id and c.marketCode in :markets")
  Optional<Claim> lockByIdAndMarketCodeIn(@Param("id") UUID id, @Param("markets") Collection<String> markets);
}
