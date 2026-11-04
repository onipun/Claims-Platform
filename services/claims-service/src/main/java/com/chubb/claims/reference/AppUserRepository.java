package com.chubb.claims.reference;

import com.chubb.claims.claim.domain.Role;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
  Optional<AppUser> findByEmail(String email);

  @Query("select distinct u from AppUser u join u.markets m where u.role in :roles and m in :markets order by u.name")
  List<AppUser> findStaffInMarkets(@Param("roles") Collection<Role> roles, @Param("markets") Collection<String> markets);
}
