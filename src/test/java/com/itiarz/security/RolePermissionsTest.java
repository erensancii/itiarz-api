package com.itiarz.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Spec §4.1 role → permission table. */
class RolePermissionsTest {

    @ParameterizedTest(name = "{0}: viewer={1} analyst={2} approver={3} admin={4}")
    @CsvSource({
        "CASE_READ,       true,  true,  true,  true",
        "EVIDENCE_READ,   true,  true,  true,  true",
        "CASE_WRITE,      false, true,  true,  true",
        "EVIDENCE_WRITE,  false, true,  true,  true",
        "CASE_ASSIGN,     false, true,  true,  true",
        "CASE_APPROVE,    false, false, true,  true",
        "CASE_CLOSE,      false, false, true,  true",
        "MEMBER_READ,     false, true,  true,  true",
        "MEMBER_MANAGE,   false, false, false, true",
        "AUDIT_READ,      false, false, false, true",
        "TENANT_SETTINGS, false, false, false, true",
        "TENANT_CREATE,       false, false, false, false",
        "TENANT_SUSPEND,      false, false, false, false",
        "TENANT_INVITE_ADMIN, false, false, false, false",
        "PLATFORM_AUDIT_READ, false, false, false, false",
    })
    void roleTableMatchesSpec(Permission permission, boolean viewer, boolean analyst,
                              boolean approver, boolean admin) {
        Map<TenantRole, Boolean> expected = Map.of(
                TenantRole.VIEWER, viewer,
                TenantRole.ANALYST, analyst,
                TenantRole.APPROVER, approver,
                TenantRole.TENANT_ADMIN, admin);
        expected.forEach((role, granted) ->
                assertThat(RolePermissions.of(role).contains(permission))
                        .as("%s has %s", role, permission)
                        .isEqualTo(granted));
    }

    @Test
    void platformPermissionsExcludeTenantData() {
        assertThat(RolePermissions.PLATFORM).doesNotContain(Permission.CASE_READ);
        assertThat(RolePermissions.PLATFORM).containsExactlyInAnyOrder(
                Permission.TENANT_CREATE, Permission.TENANT_SUSPEND,
                Permission.TENANT_INVITE_ADMIN, Permission.PLATFORM_AUDIT_READ);
    }

    @Test
    void permissionCodesAreStable() {
        assertThat(Permission.CASE_READ.code()).isEqualTo("case:read");
        assertThat(Permission.CASE_APPROVE.code()).isEqualTo("case:approve");
        assertThat(Permission.TENANT_CREATE.code()).isEqualTo("tenant:create");
        assertThat(Permission.TENANT_INVITE_ADMIN.code()).isEqualTo("tenant:invite-admin");
        assertThat(Permission.PLATFORM_AUDIT_READ.code()).isEqualTo("platform-audit:read");
        assertThat(EnumSet.allOf(Permission.class)).allSatisfy(p -> assertThat(p.code()).contains(":"));
    }
}
