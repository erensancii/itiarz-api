package com.itiarz.membership;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.itiarz.audit.AuditActions;
import com.itiarz.audit.AuditService;

/**
 * Finds the membership that grants a user access to a tenant, activating a pending invitation on the
 * way. Must run inside a transaction whose RLS context selects {@code tenantId} (the INVITED row has no
 * user id yet, so only the tenant policy makes it visible).
 */
@Component
public class MembershipResolver {
    private final MembershipRepository memberships;
    private final AuditService audit;

    public MembershipResolver(MembershipRepository memberships, AuditService audit) {
        this.memberships = memberships;
        this.audit = audit;
    }

    /**
     * The user's ACTIVE membership in the tenant. Without one, an INVITED row whose {@code invited_email}
     * equals the token e-mail is activated (user bound, {@code member.activated} audited), but only when
     * the e-mail is verified; otherwise empty.
     *
     * @param normalizedEmail token e-mail, already trimmed and lower-cased with {@code Locale.ROOT}
     */
    public Optional<Membership> resolveActive(UUID tenantId, AppUser user, String normalizedEmail,
                                              boolean emailVerified) {
        Optional<Membership> active =
                memberships.findByTenantIdAndUserIdAndStatus(tenantId, user.getId(), MembershipStatus.ACTIVE);
        if (active.isPresent() || !emailVerified || normalizedEmail == null || normalizedEmail.isBlank()) {
            return active;
        }
        // locked: concurrent first requests of one user activate (and audit) once
        return memberships.lockByTenantIdAndInvitedEmail(tenantId, normalizedEmail)
                .filter(m -> m.getStatus() == MembershipStatus.INVITED)
                .map(invitation -> {
                    // The user may still be linked to an older non-active row of this tenant (e.g. removed
                    // under a previous e-mail); release it, or binding would violate uq_memberships_tenant_user.
                    memberships.findByTenantIdAndUserId(tenantId, user.getId()).stream()
                            .filter(other -> !other.getId().equals(invitation.getId()))
                            .forEach(Membership::unlinkUser);
                    memberships.flush();
                    invitation.activate(user.getId());
                    memberships.flush();
                    audit.record(AuditActions.MEMBER_ACTIVATED, "membership", invitation.getId().toString(),
                            Map.of("email", normalizedEmail, "role", invitation.getRole().name()));
                    return invitation;
                });
    }
}
