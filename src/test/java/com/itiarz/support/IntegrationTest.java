package com.itiarz.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.itiarz.security.SecurityConfig;

/**
 * Full-stack test base: real Postgres (app role for the datasource, owner role for Flyway) and a
 * JwtDecoder that trusts {@link TestTokens} but runs the production validators.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.seed-demo=false")
@Import(IntegrationTest.TestSecurity.class)
public abstract class IntegrationTest {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected FakeKeycloakGateway fakeKeycloak;

    /** The fake is a singleton in the cached context: clear state and pending failures between tests. */
    @org.junit.jupiter.api.BeforeEach
    void resetFakeKeycloak() {
        fakeKeycloak.reset();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PostgresSupport.container().getJdbcUrl());
        registry.add("spring.datasource.username", () -> PostgresSupport.APP_USER);
        registry.add("spring.datasource.password", () -> PostgresSupport.APP_PASSWORD);
        registry.add("spring.flyway.url", () -> PostgresSupport.container().getJdbcUrl());
        registry.add("spring.flyway.user", () -> PostgresSupport.OWNER_USER);
        registry.add("spring.flyway.password", () -> PostgresSupport.OWNER_PASSWORD);
        registry.add("spring.flyway.placeholders.appRole", () -> PostgresSupport.APP_USER);
    }

    @TestConfiguration
    static class TestSecurity {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TestTokens.publicKey()).build();
            decoder.setJwtValidator(SecurityConfig.jwtValidator(TestTokens.ISSUER));
            return decoder;
        }

        /** Replaces the HTTP gateway so no integration test needs a running Keycloak. */
        @Bean
        @Primary
        FakeKeycloakGateway fakeKeycloakGateway() {
            return new FakeKeycloakGateway();
        }
    }

    /**
     * Test-only endpoint guarded by a permission, to exercise method-security denial. Registered by
     * component scanning (test classes live under com.itiarz).
     */
    @RestController
    public static class ProbeController {
        public static final String MEMBER_MANAGE_PATH = "/api/test-probe/member-manage";

        @GetMapping(MEMBER_MANAGE_PATH)
        @PreAuthorize("hasAuthority('member:manage')")
        public String memberManage() {
            return "ok";
        }
    }

    /** Keycloak user id used for app_users created by {@link #seedMember}. */
    protected static String subOf(String email) {
        return "kc-" + email.toLowerCase(Locale.ROOT);
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    protected ResponseEntity<String> postJson(String path, String token, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    protected ResponseEntity<String> patchJson(String path, String token, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.PATCH, new HttpEntity<>(json, headers), String.class);
    }

    protected ResponseEntity<String> delete(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
    }

    /** Inserts a tenant through the platform path (tenants allow writes only when app.platform='on'). */
    protected UUID seedTenant(String slug, String status) {
        return seedTenant(slug, status, UUID.randomUUID().toString());
    }

    /** Like {@link #seedTenant(String, String)} with an explicit Keycloak organization id (e.g. a fake org). */
    protected UUID seedTenant(String slug, String status, String keycloakOrgId) {
        UUID id = UUID.randomUUID();
        try (Connection c = platformConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO tenants (id, keycloak_org_id, slug, name, status) VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, keycloakOrgId);
            ps.setString(3, slug);
            ps.setString(4, "Tenant " + slug);
            ps.setString(5, status);
            ps.executeUpdate();
            return id;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Adds a membership for {@code email} in the tenant {@code slug}. The app user (keycloak id
     * {@link #subOf}) is created on demand; INVITED memberships carry no user id.
     */
    protected UUID seedMember(String slug, String email, String role, String status) {
        String normalized = email.toLowerCase(Locale.ROOT);
        try (Connection c = platformConnection()) {
            UUID tenantId = queryUuid(c, "SELECT id FROM tenants WHERE slug = ?", slug);
            UUID userId = null;
            if (!"INVITED".equals(status)) {
                userId = queryUuid(c, "SELECT id FROM app_users WHERE keycloak_user_id = ?", subOf(normalized));
                if (userId == null) {
                    userId = UUID.randomUUID();
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO app_users (id, keycloak_user_id, email, display_name) VALUES (?, ?, ?, ?)")) {
                        ps.setObject(1, userId);
                        ps.setString(2, subOf(normalized));
                        ps.setString(3, normalized);
                        ps.setString(4, "Test " + normalized);
                        ps.executeUpdate();
                    }
                }
            }
            UUID id = UUID.randomUUID();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO memberships (id, tenant_id, user_id, invited_email, role, status) "
                            + "VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setObject(1, id);
                ps.setObject(2, tenantId);
                ps.setObject(3, userId);
                ps.setString(4, normalized);
                ps.setString(5, role);
                ps.setString(6, status);
                ps.executeUpdate();
            }
            return id;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    protected long countAppUsers(String keycloakUserId) {
        try (Connection c = PostgresSupport.appConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM app_users WHERE keycloak_user_id = ?")) {
            ps.setString(1, keycloakUserId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Connection platformConnection() throws SQLException {
        Connection c = PostgresSupport.appConnection();
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.platform', 'on', false)")) {
            ps.execute();
        }
        return c;
    }

    private static UUID queryUuid(Connection c, String sql, String arg) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, UUID.class) : null;
            }
        }
    }
}
