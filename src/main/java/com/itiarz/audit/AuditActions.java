package com.itiarz.audit;

/** Fixed audit action names (stored in {@code audit_events.action}). */
public final class AuditActions {
    public static final String CASE_CREATED = "case.created";
    public static final String CASE_EVIDENCE_ADDED = "case.evidence_added";
    public static final String CASE_TRANSITION = "case.transition";
    public static final String CASE_ASSIGNED = "case.assigned";
    public static final String MEMBER_INVITED = "member.invited";
    public static final String MEMBER_ROLE_CHANGED = "member.role_changed";
    public static final String MEMBER_REMOVED = "member.removed";
    public static final String MEMBER_ACTIVATED = "member.activated";
    public static final String TENANT_CREATED = "tenant.created";
    public static final String TENANT_SUSPENDED = "tenant.suspended";
    public static final String TENANT_ACTIVATED = "tenant.activated";
    public static final String TENANT_INVITE_FAILED = "tenant.invite_failed";

    private AuditActions() {
    }
}
