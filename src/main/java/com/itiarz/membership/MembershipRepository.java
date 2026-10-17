package com.itiarz.membership;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    Optional<Membership> findByTenantIdAndUserIdAndStatus(UUID tenantId, UUID userId, MembershipStatus status);

    /** The caller's own rows across tenants; visible through the memberships_self_read policy (app.user_id). */
    List<Membership> findByUserIdAndStatus(UUID userId, MembershipStatus status);
}
