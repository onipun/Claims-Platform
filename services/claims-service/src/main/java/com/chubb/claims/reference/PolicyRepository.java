package com.chubb.claims.reference;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRepository extends JpaRepository<Policy, UUID> {
  List<Policy> findByHolderIdOrderByPolicyNumber(UUID holderId);
}
