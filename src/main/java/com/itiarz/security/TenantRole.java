package com.itiarz.security;

/** Tenant-scoped roles; stored in memberships.role, never in Keycloak. */
public enum TenantRole {
    TENANT_ADMIN, APPROVER, ANALYST, VIEWER
}
