package com.itiarz.keycloak;

/** Keycloak could not be reached or answered with a server/authorization failure; mapped to 502. */
public class KeycloakUnavailableException extends RuntimeException {
    public KeycloakUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
