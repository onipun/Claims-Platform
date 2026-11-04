package com.chubb.claims.claim;

import com.chubb.claims.claim.domain.ClaimStatus;
import com.chubb.claims.claim.domain.Decision.Visibility;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimActivityRepository extends JpaRepository<ClaimActivity, Long> {

  record UpdateRow(Long id, UUID claimId, String claimNumber, String type, String message,
      ClaimStatus fromStatus, ClaimStatus toStatus, UUID actorId, Instant createdAt) {}

  @Query(value = """
      select new com.chubb.claims.claim.ClaimActivityRepository$UpdateRow(
          a.id, c.id, c.claimNumber, a.type, a.message, a.fromStatus, a.toStatus, a.actorId, a.createdAt)
      from ClaimActivity a join a.claim c
      where c.claimantId = :claimantId and a.visibility = :visibility
      order by a.createdAt desc, a.id desc""",
      countQuery = """
      select count(a) from ClaimActivity a join a.claim c
      where c.claimantId = :claimantId and a.visibility = :visibility""")
  Page<UpdateRow> findVisibleUpdates(
      @Param("claimantId") UUID claimantId, @Param("visibility") Visibility visibility, Pageable pageable);
}
