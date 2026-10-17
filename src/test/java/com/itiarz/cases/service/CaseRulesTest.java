package com.itiarz.cases.service;

import static com.itiarz.cases.domain.CaseStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.itiarz.cases.domain.CaseReason;
import com.itiarz.cases.domain.CaseStatus;
import com.itiarz.security.Permission;

class CaseRulesTest {

    @Test
    void fraudRequiresPaymentAnd3dsEvidence() {
        assertThat(CaseRules.requiredEvidence(CaseReason.Fraud)).isEqualTo(List.of("PaymentRecord", "3DSRecord"));
    }

    @Test
    void preparingTransitionsRequireCaseWrite() {
        assertThat(CaseRules.requiredPermission(Open, InProgress)).contains(Permission.CASE_WRITE);
        assertThat(CaseRules.requiredPermission(Open, Ready)).contains(Permission.CASE_WRITE);
        assertThat(CaseRules.requiredPermission(InProgress, Ready)).contains(Permission.CASE_WRITE);
    }

    @Test
    void sendBackIsValidAndRequiresCaseApprove() {
        assertThat(CaseRules.requiredPermission(Ready, InProgress)).contains(Permission.CASE_APPROVE);
    }

    @Test
    void submitRequiresCaseApprove() {
        assertThat(CaseRules.requiredPermission(Ready, Submitted)).contains(Permission.CASE_APPROVE);
    }

    @Test
    void outcomeRequiresCaseClose() {
        assertThat(CaseRules.requiredPermission(Submitted, Won)).contains(Permission.CASE_CLOSE);
        assertThat(CaseRules.requiredPermission(Submitted, Lost)).contains(Permission.CASE_CLOSE);
    }

    @Test
    void skippingStepsIsInvalid() {
        assertThat(CaseRules.requiredPermission(Open, Submitted)).isEmpty();
        assertThat(CaseRules.requiredPermission(InProgress, Submitted)).isEmpty();
        assertThat(CaseRules.requiredPermission(Ready, Won)).isEmpty();
        assertThat(CaseRules.requiredPermission(Submitted, Ready)).isEmpty();
        assertThat(CaseRules.requiredPermission(InProgress, Open)).isEmpty();
        assertThat(CaseRules.requiredPermission(Ready, Ready)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(CaseStatus.class)
    void closedCasesAreImmutable(CaseStatus to) {
        assertThat(CaseRules.requiredPermission(Won, to)).isEqualTo(Optional.empty());
        assertThat(CaseRules.requiredPermission(Lost, to)).isEqualTo(Optional.empty());
    }
}
