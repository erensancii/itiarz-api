package com.itiarz.common;

/** Machine-readable {@code code} of problem responses (spec §8). */
public enum ErrorCode {
    CASE_SELF_APPROVAL,
    LAST_ADMIN,
    DUPLICATE_INVITE,
    DUPLICATE_CASE,
    INVALID_TRANSITION,
    CONFLICT,
    EVIDENCE_INCOMPLETE,
    TENANT_SUSPENDED,
    NO_MEMBERSHIP,
    TENANT_NOT_SELECTED,
    KEYCLOAK_UNAVAILABLE,
    VALIDATION,
    NOT_FOUND,
    UNAUTHENTICATED,
    FORBIDDEN,
    INTERNAL
}
