package com.disputepilot.service;

import com.disputepilot.domain.CaseReason;
import com.disputepilot.domain.CaseStatus;
import java.util.List;
import java.util.Set;

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

    public static boolean validTransition(CaseStatus from, CaseStatus to) {
        return switch (from) {
            case Open -> to == CaseStatus.InProgress || to == CaseStatus.Ready;
            case InProgress -> to == CaseStatus.Ready;
            case Ready -> to == CaseStatus.Submitted;
            case Submitted -> to == CaseStatus.Won || to == CaseStatus.Lost;
            case Won, Lost -> false;
        };
    }
}
