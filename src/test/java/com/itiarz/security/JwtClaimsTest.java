package com.itiarz.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtClaimsTest {

    @Test
    void organizationAsListOfAliases() {
        JwtClaims claims = JwtClaims.from(token(Map.of("organization", List.of("acme", "globex"))));

        assertThat(claims.organizations()).containsExactly("acme", "globex");
    }

    @Test
    void organizationAsMapKeyedByAlias() {
        JwtClaims claims = JwtClaims.from(token(Map.of("organization",
                Map.of("acme", Map.of("id", "4f1c")))));

        assertThat(claims.organizations()).containsExactly("acme");
    }

    @Test
    void missingOrganizationAndRolesAreEmpty() {
        JwtClaims claims = JwtClaims.from(token(Map.of()));

        assertThat(claims.organizations()).isEmpty();
        assertThat(claims.platformAdmin()).isFalse();
    }

    @Test
    void emailVerifiedOnlyWhenExplicitlyTrue() {
        assertThat(JwtClaims.from(token(Map.of("email_verified", true))).emailVerified()).isTrue();
        assertThat(JwtClaims.from(token(Map.of("email_verified", false))).emailVerified()).isFalse();
        assertThat(JwtClaims.from(token(Map.of())).emailVerified()).isFalse();
    }

    @Test
    void emailWithCapitalIIsNormalisedLocaleIndependently() {
        java.util.Locale previous = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
        try {
            assertThat(JwtClaims.from(token(Map.of("email", "IPEK@ACME.LOCAL"))).email())
                    .isEqualTo("ipek@acme.local");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    void emailIsNormalisedAndPlatformAdminRead() {
        JwtClaims claims = JwtClaims.from(token(Map.of(
                "email", "Ayse.YILMAZ@Example.COM",
                "realm_access", Map.of("roles", List.of("platform-admin")))));

        assertThat(claims.email()).isEqualTo("ayse.yilmaz@example.com");
        assertThat(claims.platformAdmin()).isTrue();
        assertThat(claims.subject()).isEqualTo("kc-1");
    }

    private static Jwt token(Map<String, Object> extra) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject("kc-1");
        extra.forEach(builder::claim);
        return builder.build();
    }
}
