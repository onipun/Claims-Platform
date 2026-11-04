package com.chubb.claims.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ClaimDocumentRepository extends JpaRepository<ClaimDocument, UUID> {
  List<ClaimDocument> findByClaimIdOrderByUploadedAtAscIdAsc(UUID claimId);

  /** A document is only reachable through its own claim: a mismatched pair is simply not found (trap 6). */
  Optional<ClaimDocument> findByIdAndClaimId(UUID id, UUID claimId);

  long countByClaimId(UUID claimId);
}
