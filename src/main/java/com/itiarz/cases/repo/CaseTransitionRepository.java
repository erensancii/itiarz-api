package com.itiarz.cases.repo;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itiarz.cases.domain.CaseTransition;

public interface CaseTransitionRepository extends JpaRepository<CaseTransition, UUID> {
    List<CaseTransition> findByTenantIdAndCaseIdOrderByOccurredAtAscIdAsc(UUID tenantId, UUID caseId);
}
