package com.itiarz.security;

/** Fine-grained permissions; code() is the Spring authority, e.g. {@code hasAuthority('case:approve')}. */
public enum Permission {
    CASE_READ("case:read"),
    CASE_WRITE("case:write"),
    CASE_ASSIGN("case:assign"),
    CASE_APPROVE("case:approve"),
    CASE_CLOSE("case:close"),
    EVIDENCE_READ("evidence:read"),
    EVIDENCE_WRITE("evidence:write"),
    MEMBER_READ("member:read"),
    MEMBER_MANAGE("member:manage"),
    AUDIT_READ("audit:read"),
    TENANT_SETTINGS("tenant:settings"),
    TENANT_CREATE("tenant:create"),
    TENANT_SUSPEND("tenant:suspend"),
    TENANT_INVITE_ADMIN("tenant:invite-admin"),
    PLATFORM_AUDIT_READ("platform-audit:read");

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
