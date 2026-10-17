package com.itiarz.security;

import static com.itiarz.security.Permission.*;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The single role → permission mapping (spec §4.1). Code checks permissions, never role names. */
public final class RolePermissions {

    /** Granted only by the {@code platform-admin} realm role; deliberately excludes all tenant data. */
    public static final Set<Permission> PLATFORM = Collections.unmodifiableSet(
            EnumSet.of(TENANT_CREATE, TENANT_SUSPEND, TENANT_INVITE_ADMIN, PLATFORM_AUDIT_READ));

    private static final Map<TenantRole, Set<Permission>> BY_ROLE = new EnumMap<>(TenantRole.class);

    static {
        Set<Permission> viewer = EnumSet.of(CASE_READ, EVIDENCE_READ);
        Set<Permission> analyst = union(viewer, EnumSet.of(CASE_WRITE, EVIDENCE_WRITE, CASE_ASSIGN, MEMBER_READ));
        Set<Permission> approver = union(analyst, EnumSet.of(CASE_APPROVE, CASE_CLOSE));
        Set<Permission> admin = union(approver, EnumSet.of(MEMBER_MANAGE, AUDIT_READ, TENANT_SETTINGS));
        BY_ROLE.put(TenantRole.VIEWER, Collections.unmodifiableSet(viewer));
        BY_ROLE.put(TenantRole.ANALYST, Collections.unmodifiableSet(analyst));
        BY_ROLE.put(TenantRole.APPROVER, Collections.unmodifiableSet(approver));
        BY_ROLE.put(TenantRole.TENANT_ADMIN, Collections.unmodifiableSet(admin));
    }

    private RolePermissions() {
    }

    public static Set<Permission> of(TenantRole role) {
        return BY_ROLE.get(role);
    }

    /** Effective permissions of a request: the tenant role's (if any) plus platform permissions for platform admins. */
    public static Set<Permission> granted(TenantRole roleOrNull, boolean platformAdmin) {
        EnumSet<Permission> result = EnumSet.noneOf(Permission.class);
        if (roleOrNull != null) {
            result.addAll(of(roleOrNull));
        }
        if (platformAdmin) {
            result.addAll(PLATFORM);
        }
        return result;
    }

    private static Set<Permission> union(Set<Permission> base, Set<Permission> extra) {
        EnumSet<Permission> result = EnumSet.copyOf(base);
        result.addAll(extra);
        return result;
    }
}
