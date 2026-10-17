package com.itiarz.cases.service;

import static com.itiarz.cases.domain.CaseStatus.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.itiarz.cases.domain.CaseReason;
import com.itiarz.cases.domain.CaseStatus;
import com.itiarz.security.Permission;

/** Pure case rules: required evidence per reason and the state machine with its permissions (spec §6.5). */
public final class CaseRules {
    private CaseRules() { }

    public static final Set<String> ALLOWED_EVIDENCE = Set.of(
        "PaymentRecord", "OrderConfirmation", "DeliveryProof", "3DSRecord",
        "CustomerCommunication", "RefundRecord", "CancellationPolicy");

    // DEMO RULES ONLY: not Visa, Mastercard, TROY or any PSP's official requirements.
    public static List<String> requiredEvidence(CaseReason reason) {
        return switch (reason) {
            case NotReceived -> List.of("PaymentRecord", "DeliveryProof");
            case Fraud -> List.of("PaymentRecord", "3DSRecord");
            case Duplicate -> List.of("PaymentRecord", "RefundRecord");
            case Cancelled -> List.of("PaymentRecord", "CancellationPolicy");
            case Other -> List.of("PaymentRecord", "OrderConfirmation");
        };
    }

    /** Permission needed for {@code from → to}; empty when the transition is not allowed at all. */
    public static Optional<Permission> requiredPermission(CaseStatus from, CaseStatus to) {
        Permission permission = switch (from) {
            case Open -> to == InProgress || to == Ready ? Permission.CASE_WRITE : null;
            case InProgress -> to == Ready ? Permission.CASE_WRITE : null;
            case Ready -> to == InProgress || to == Submitted ? Permission.CASE_APPROVE : null;
            case Submitted -> to == Won || to == Lost ? Permission.CASE_CLOSE : null;
            case Won, Lost -> null;
        };
        return Optional.ofNullable(permission);
    }
}
