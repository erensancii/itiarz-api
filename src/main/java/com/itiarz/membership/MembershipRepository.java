package com.itiarz.membership;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itiarz.security.TenantRole;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    Optional<Membership> findByTenantIdAndUserIdAndStatus(UUID tenantId, UUID userId, MembershipStatus status);

    /** The caller's own rows across tenants; visible through the memberships_self_read policy (app.user_id). */
    List<Membership> findByUserIdAndStatus(UUID userId, MembershipStatus status);

    /** The row for a (normalized) e-mail in a tenant, any status; unique by (tenant_id, invited_email). */
    Optional<Membership> findByTenantIdAndInvitedEmail(UUID tenantId, String invitedEmail);

    List<Membership> findByTenantIdAndStatusNot(UUID tenantId, MembershipStatus status);

    /** True when a non-removed member of the tenant is linked to a user whose current e-mail is {@code email}. */
    @Query("""
            select count(m) > 0 from Membership m, AppUser u
            where m.userId = u.id and m.tenantId = :tenantId
              and m.status <> com.itiarz.membership.MembershipStatus.REMOVED and lower(u.email) = :email
            """)
    boolean existsLiveMemberWithUserEmail(@Param("tenantId") UUID tenantId, @Param("email") String email);

    /**
     * Locks the tenant's active rows with {@code role} (FOR UPDATE), so concurrent demotions/removals of
     * admins serialize and the last-admin check cannot be raced.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Membership m where m.tenantId = :tenantId and m.role = :role "
            + "and m.status = com.itiarz.membership.MembershipStatus.ACTIVE")
    List<Membership> lockActiveByRole(@Param("tenantId") UUID tenantId, @Param("role") TenantRole role);
}
