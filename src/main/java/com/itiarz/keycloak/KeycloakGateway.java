package com.itiarz.keycloak;

import java.util.Optional;

/** Keycloak Admin API operations itiarz needs (organizations, invitations, user lookup). */
public interface KeycloakGateway {
    /** Creates an organization and returns its id; an alias that already exists is a 409 DomainException. */
    String createOrganization(String name, String alias);

    void deleteOrganization(String orgId);

    Optional<String> findOrganizationIdByAlias(String alias);

    /** Keycloak sends the invitation e-mail. */
    void inviteToOrganization(String orgId, String email);

    void addOrganizationMember(String orgId, String keycloakUserId);

    void removeOrganizationMember(String orgId, String keycloakUserId);

    Optional<String> findUserIdByEmail(String email);
}
