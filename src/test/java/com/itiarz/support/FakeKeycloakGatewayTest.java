package com.itiarz.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.itiarz.keycloak.KeycloakRequestException;

/** Parity of the fake with the HTTP gateway for unknown ids. */
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
}
