package com.itiarz.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Admin API connection: {@code app.keycloak.*} (internal URL, realm and the client-credentials client). */
@ConfigurationProperties("app.keycloak")
public record KeycloakProperties(String internalUrl, String realm, String adminClientId, String adminClientSecret) {
}
