package com.chubb.claims.reference;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRequirementRepository extends JpaRepository<DocumentRequirement, UUID> {
  List<DocumentRequirement> findByMarketCodeAndLineAndClaimTypeOrderByDocumentType(
      String marketCode, String line, String claimType);
}
