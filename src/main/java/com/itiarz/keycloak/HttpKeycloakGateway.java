package com.itiarz.keycloak;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.itiarz.common.DomainException;
import com.itiarz.common.ErrorCode;

/**
 * Keycloak Admin REST implementation. Connection problems, 5xx and failed admin authentication become
 * {@link KeycloakUnavailableException}; 409 becomes a CONFLICT {@link DomainException}.
 *
 * <p>Verified against Keycloak 26.8.1 (HttpKeycloakGatewayIT).
 *
 * <ul>
 *   <li>Organization domains are NOT required: an organization is created with just name + alias, so no
 *       {@code domains} are sent.</li>
 *   <li>{@code GET /organizations?search=} matches name/domain, not alias; lookup by alias therefore pages through
 *       the list and filters on alias.</li>
 *   <li>Adding an existing member answers 409; invite-user answers success and sends the e-mail through the realm SMTP
 *       (mailpit in the IT).</li>
 * </ul>
 */
public class HttpKeycloakGateway implements KeycloakGateway {
    private static final Logger log = LoggerFactory.getLogger(HttpKeycloakGateway.class);
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAPS =
            new ParameterizedTypeReference<>() { };

    private static final int ORG_PAGE = 100;

    private final RestClient client;
    private final String base;

    public HttpKeycloakGateway(RestClient client, String realm) {
        this.client = client;
        this.base = "/admin/realms/" + realm;
    }

    @Override
    public String createOrganization(String name, String alias) {
        Map<String, Object> body = Map.of("name", name, "alias", alias, "enabled", true);
        ResponseEntity<Void> response = call(() -> client.post().uri(base + "/organizations")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity(), false);
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new KeycloakUnavailableException("Keycloak did not return an organization location", null);
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Override
    public void deleteOrganization(String orgId) {
        call(() -> client.delete().uri(base + "/organizations/{id}", orgId).retrieve().toBodilessEntity(), true);
    }

    @Override
    public Optional<String> findOrganizationIdByAlias(String alias) {
        String wanted = alias.toLowerCase(Locale.ROOT);
        // Keycloak's ?search= matches organization name and domain, not alias, so page through and filter
        for (int first = 0; ; first += ORG_PAGE) {
            int offset = first;
            List<Map<String, Object>> page = call(() -> client.get()
                    .uri(base + "/organizations?first={f}&max={m}", offset, ORG_PAGE)
                    .retrieve().body(LIST_OF_MAPS), false);
            if (page == null || page.isEmpty()) {
                return Optional.empty();
            }
            Optional<String> match = firstMatching(page, "alias", wanted);
            if (match.isPresent() || page.size() < ORG_PAGE) {
                return match;
            }
        }
    }

    @Override
    public void inviteToOrganization(String orgId, String email) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("email", email.toLowerCase(Locale.ROOT));
        call(() -> client.post().uri(base + "/organizations/{id}/members/invite-user", orgId)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toBodilessEntity(), false);
    }

    @Override
    public void addOrganizationMember(String orgId, String keycloakUserId) {
        // the body is a bare JSON string: the user id
        call(() -> client.post().uri(base + "/organizations/{id}/members", orgId)
                .contentType(MediaType.APPLICATION_JSON).body("\"" + keycloakUserId + "\"")
                .retrieve().toBodilessEntity(), false);
    }

    @Override
    public void removeOrganizationMember(String orgId, String keycloakUserId) {
        call(() -> client.delete().uri(base + "/organizations/{id}/members/{uid}", orgId, keycloakUserId)
                .retrieve().toBodilessEntity(), true);
    }

    @Override
    public Optional<String> findUserIdByEmail(String email) {
        String wanted = email.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> users = call(() -> client.get()
                .uri(base + "/users?email={e}&exact=true", wanted)
                .retrieve().body(LIST_OF_MAPS), false);
        return firstMatching(users, "email", wanted);
    }

    private static Optional<String> firstMatching(List<Map<String, Object>> items, String field, String wanted) {
        if (items == null) {
            return Optional.empty();
        }
        return items.stream()
                .filter(item -> wanted.equalsIgnoreCase(String.valueOf(item.get(field))))
                .map(item -> String.valueOf(item.get("id")))
                .findFirst();
    }

    /** @param tolerateNotFound a 404 means "already gone" (null result) instead of a failure */
    private <T> T call(Supplier<T> action, boolean tolerateNotFound) {
        try {
            return action.get();
        } catch (RestClientResponseException ex) {
            HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
            if (status == HttpStatus.CONFLICT) {
                throw new DomainException(HttpStatus.CONFLICT, ErrorCode.CONFLICT,
                        "Bu kayıt Keycloak tarafında zaten mevcut. Bilgileri kontrol edip tekrar deneyin.");
            }
            if (status == HttpStatus.NOT_FOUND && tolerateNotFound) {
                return null;
            }
            log.error("Keycloak admin call failed: {} {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new KeycloakUnavailableException("Keycloak admin API returned " + ex.getStatusCode(), ex);
        } catch (DomainException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // connection/timeouts (ResourceAccessException) and token-fetch failures
            log.error("Keycloak admin call failed", ex);
            throw new KeycloakUnavailableException("Keycloak is unreachable or rejected admin credentials", ex);
        }
    }
}
