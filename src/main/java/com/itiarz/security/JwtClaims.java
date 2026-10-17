package com.itiarz.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The Keycloak claims the backend uses (spec §3.2). {@code organization} arrives either as a list
 * of aliases or, when org id/attributes are mapped, as an object keyed by alias.
 */
public record JwtClaims(String subject, String email, String name, boolean platformAdmin,
                        List<String> organizations) {

    public static final String PLATFORM_ADMIN_ROLE = "platform-admin";

    public static JwtClaims from(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return new JwtClaims(
                jwt.getSubject(),
                email == null ? null : email.trim().toLowerCase(Locale.ROOT),
                jwt.getClaimAsString("name"),
                realmRoles(jwt).contains(PLATFORM_ADMIN_ROLE),
                organizations(jwt.getClaim("organization")));
    }

    private static List<String> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
            return roles.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static List<String> organizations(Object claim) {
        List<String> result = new ArrayList<>();
        if (claim instanceof Map<?, ?> byAlias) {
            byAlias.keySet().forEach(alias -> result.add(String.valueOf(alias)));
        } else if (claim instanceof Collection<?> aliases) {
            aliases.forEach(alias -> result.add(String.valueOf(alias)));
        } else if (claim instanceof String alias) {
            result.add(alias);
        }
        return List.copyOf(result);
    }
}
