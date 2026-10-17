package com.itiarz.cases.repo;

import com.itiarz.cases.domain.DisputeCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every finder takes the tenant explicitly; RLS enforces the same scope underneath. */
public interface DisputeCaseRepository extends JpaRepository<DisputeCase, UUID> {
    @Query("select distinct c from DisputeCase c left join fetch c.evidence "
        + "where c.tenantId = :tenantId order by c.deadlineUtc asc, c.id asc")
    List<DisputeCase> findAllByTenantIdOrderByDeadlineUtc(@Param("tenantId") UUID tenantId);

    @Query("select c from DisputeCase c left join fetch c.evidence where c.id = :id and c.tenantId = :tenantId")
    Optional<DisputeCase> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    boolean existsByTenantIdAndProviderAndProviderCaseId(UUID tenantId, String provider, String providerCaseId);
}
