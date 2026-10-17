package com.itiarz.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.itiarz.common.DomainException;
import com.itiarz.keycloak.KeycloakRequestException;

/** Parity of the fake with the HTTP gateway (see HttpKeycloakGatewayIT for the real behaviour). */
class FakeKeycloakGatewayTest {

    @Test
    void unknownOrganizationIsARequestError() {
        FakeKeycloakGateway fake = new FakeKeycloakGateway();
        String user = fake.registerUser("a@b.c");
        assertThatThrownBy(() -> fake.addOrganizationMember("nope", user)).isInstanceOf(KeycloakRequestException.class);
        assertThatThrownBy(() -> fake.inviteToOrganization("nope", "a@b.c")).isInstanceOf(KeycloakRequestException.class);
    }

    @Test
    void unknownUserIsARequestError() {
        FakeKeycloakGateway fake = new FakeKeycloakGateway();
        String org = fake.createOrganization("O", "o");
        assertThatThrownBy(() -> fake.addOrganizationMember(org, "ghost")).isInstanceOf(KeycloakRequestException.class);
    }

    @Test
    void duplicateNameIsConflict() {
        FakeKeycloakGateway fake = new FakeKeycloakGateway();
        fake.createOrganization("Acme", "acme");
        assertThatThrownBy(() -> fake.createOrganization("Acme", "acme-2")).isInstanceOfSatisfying(
                DomainException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void reinvitingPendingInvitationOrMemberIsConflict() {
        FakeKeycloakGateway fake = new FakeKeycloakGateway();
        String org = fake.createOrganization("O", "o");
        fake.inviteToOrganization(org, "a@b.c");
        assertThatThrownBy(() -> fake.inviteToOrganization(org, "A@b.c")).isInstanceOfSatisfying(
                DomainException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT));

        String member = fake.registerUser("m@b.c");
        fake.addOrganizationMember(org, member);
        assertThatThrownBy(() -> fake.inviteToOrganization(org, "m@b.c")).isInstanceOfSatisfying(
                DomainException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT));

        // a pending invitation ends when the invited user joins; after removal a new invitation works
        String joined = fake.registerUser("a@b.c");
        fake.addOrganizationMember(org, joined);
        fake.removeOrganizationMember(org, joined);
        fake.inviteToOrganization(org, "a@b.c");
        assertThat(fake.invitations()).hasSize(2);
    }
}
