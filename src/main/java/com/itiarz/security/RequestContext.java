package com.itiarz.security;

import java.util.UUID;

/**
 * Who is calling and in which tenant. {@code tenantId}, {@code tenantSlug} and {@code role} are null
 * when the token selects no single organization (tenant-less context: /api/me, platform endpoints).
 */
public record RequestContext(UUID userId, String keycloakUserId, String email, boolean platformAdmin,
                             UUID tenantId, String tenantSlug, TenantRole role) {

    public boolean hasTenant() {
        return tenantId != null;
    }
}
