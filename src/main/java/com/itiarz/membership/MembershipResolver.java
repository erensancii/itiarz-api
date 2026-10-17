package com.itiarz.membership;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

/** Finds the membership that grants a user access to a tenant. Task 8 adds invitation activation. */
@Component
public class MembershipResolver {
    private final MembershipRepository memberships;

    public MembershipResolver(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    public Optional<Membership> resolveActive(UUID tenantId, AppUser user) {
        return memberships.findByTenantIdAndUserIdAndStatus(tenantId, user.getId(), MembershipStatus.ACTIVE);
    }
}
