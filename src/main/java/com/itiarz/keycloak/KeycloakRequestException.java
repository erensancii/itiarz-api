package com.itiarz.keycloak;

/** Keycloak rejected a request as invalid (4xx other than auth/conflict): a bug on our side, rendered as 500. */
public class KeycloakRequestException extends RuntimeException {
    public KeycloakRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
