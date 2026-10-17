package com.itiarz.security;

import org.springframework.stereotype.Component;

/**
 * For {@code @PreAuthorize("@tenant.selected() and hasAuthority(...)")}: tenant-scoped endpoints answer
 * 403 TENANT_NOT_SELECTED (not a bare FORBIDDEN) when the token selects no tenant, e.g. platform admins.
 */
@Component("tenant")
public class TenantGuard {
    public boolean selected() {
        RequestContextHolder.requireTenantId();
        return true;
    }
}
