package com.itiarz.repo;

import com.itiarz.domain.DisputeCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeCaseRepository extends JpaRepository<DisputeCase, UUID> {
    @Query("select distinct c from DisputeCase c left join fetch c.evidence order by c.deadlineUtc asc")
    List<DisputeCase> findAllWithEvidenceOrdered();

    @Query("select distinct c from DisputeCase c left join fetch c.evidence where c.id = :id")
    Optional<DisputeCase> findWithEvidenceById(@Param("id") UUID id);

    boolean existsByProviderAndProviderCaseId(String provider, String providerCaseId);
}
