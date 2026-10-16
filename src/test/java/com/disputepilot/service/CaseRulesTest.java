package com.disputepilot.service;

import com.disputepilot.domain.CaseReason;
import com.disputepilot.domain.CaseStatus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaseRulesTest {
    @Test
    void fraudRequiresPaymentAnd3dsEvidence() {
        assertEquals(java.util.List.of("PaymentRecord", "3DSRecord"), CaseRules.requiredEvidence(CaseReason.Fraud));
    }

    @Test
    void submittedCanOnlyBecomeWonOrLost() {
        assertTrue(CaseRules.validTransition(CaseStatus.Submitted, CaseStatus.Won));
        assertTrue(CaseRules.validTransition(CaseStatus.Submitted, CaseStatus.Lost));
        assertFalse(CaseRules.validTransition(CaseStatus.Submitted, CaseStatus.Ready));
    }

    @Test
    void closedCaseIsImmutable() {
        assertFalse(CaseRules.validTransition(CaseStatus.Won, CaseStatus.Open));
        assertFalse(CaseRules.validTransition(CaseStatus.Lost, CaseStatus.InProgress));
    }
}
