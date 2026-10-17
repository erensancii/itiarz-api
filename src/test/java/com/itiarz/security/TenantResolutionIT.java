package com.itiarz.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itiarz.support.IntegrationTest;
import com.itiarz.support.PostgresSupport;
import com.itiarz.support.TestTokens;

class TenantResolutionIT extends IntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void noTokenReturns401ProblemJson() {
        ResponseEntity<String> response = get("/api/me", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue());
        JsonNode body = json(response);
        assertThat(body.path("status").asInt()).isEqualTo(401);
        assertThat(body.path("traceId").asText()).isNotBlank();
    }

    @Test
    void wrongAudienceReturns401() {
        String email = unique("aud") + "@example.com";
        String token = TestTokens.jwt(subOf(email), email, List.of(), false, List.of("account"));

        assertThat(get("/api/me", token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void meListsMembershipsWithoutTenant() throws Exception {
        String email = unique("multi") + "@example.com";
        String a = unique("acme");
        String b = unique("globex");
        seedTenant(a, "ACTIVE");
        seedTenant(b, "ACTIVE");
        seedMember(a, email, "ANALYST", "ACTIVE");
        seedMember(b, email, "VIEWER", "ACTIVE");

        ResponseEntity<String> response = get("/api/me", TestTokens.jwt(subOf(email), email, null, false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(response);
        assertThat(body.path("activeTenant").isNull()).isTrue();
        assertThat(body.path("platformAdmin").asBoolean()).isFalse();
        assertThat(body.path("user").path("email").asText()).isEqualTo(email);
        assertThat(body.path("memberships")).hasSize(2);
        List<String> slugs = new ArrayList<>();
        body.path("memberships").forEach(m -> slugs.add(m.path("slug").asText()));
        assertThat(slugs).containsExactlyInAnyOrder(a, b);
        assertThat(body.path("permissions")).isEmpty();
    }

    @Test
    void activeTenantPermissionsMatchRole() throws Exception {
        String email = unique("analyst") + "@example.com";
        String acme = unique("acme");
        seedTenant(acme, "ACTIVE");
        seedMember(acme, email, "ANALYST", "ACTIVE");

        ResponseEntity<String> response = get("/api/me", TestTokens.jwt(subOf(email), email, List.of(acme), false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(response);
        assertThat(body.path("activeTenant").path("slug").asText()).isEqualTo(acme);
        assertThat(body.path("activeTenant").path("role").asText()).isEqualTo("ANALYST");
        List<String> permissions = new ArrayList<>();
        body.path("permissions").forEach(p -> permissions.add(p.asText()));
        assertThat(permissions).contains("case:write").doesNotContain("case:approve");
    }

    @Test
    void suspendedTenantReturns403() throws Exception {
        String email = unique("susp") + "@example.com";
        String slug = unique("frozen");
        seedTenant(slug, "SUSPENDED");
        seedMember(slug, email, "TENANT_ADMIN", "ACTIVE");

        ResponseEntity<String> response = get("/api/me", TestTokens.jwt(subOf(email), email, List.of(slug), false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(response).path("code").asText()).isEqualTo("TENANT_SUSPENDED");
    }

    @Test
    void removedMemberReturns403() throws Exception {
        String email = unique("removed") + "@example.com";
        String slug = unique("acme");
        seedTenant(slug, "ACTIVE");
        seedMember(slug, email, "ANALYST", "REMOVED");

        ResponseEntity<String> response = get("/api/me", TestTokens.jwt(subOf(email), email, List.of(slug), false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(response).path("code").asText()).isEqualTo("NO_MEMBERSHIP");
    }

    @Test
    void unknownOrganizationReturns403() throws Exception {
        String email = unique("stranger") + "@example.com";

        ResponseEntity<String> response = get("/api/me",
                TestTokens.jwt(subOf(email), email, List.of(unique("nowhere")), false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(response).path("code").asText()).isEqualTo("NO_MEMBERSHIP");
    }

    @Test
    void multipleOrganizationsInTokenRejectedOnTenantEndpoint() throws Exception {
        String email = unique("both") + "@example.com";
        String a = unique("acme");
        String b = unique("globex");
        seedTenant(a, "ACTIVE");
        seedTenant(b, "ACTIVE");
        seedMember(a, email, "ANALYST", "ACTIVE");
        seedMember(b, email, "ANALYST", "ACTIVE");

        ResponseEntity<String> response = get("/api/cases",
                TestTokens.jwt(subOf(email), email, List.of(a, b), false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(response).path("code").asText()).isEqualTo("TENANT_NOT_SELECTED");
    }

    @Test
    void singleOrganizationScopesTenantQueriesThroughRls() throws Exception {
        String email = unique("scoped") + "@example.com";
        String a = unique("acme");
        String b = unique("globex");
        UUID tenantA = seedTenant(a, "ACTIVE");
        UUID tenantB = seedTenant(b, "ACTIVE");
        seedMember(a, email, "VIEWER", "ACTIVE");
        seedMember(b, email, "VIEWER", "ACTIVE");
        UUID creator = appUserId(subOf(email));
        insertCase(tenantA, creator, "A-1");
        insertCase(tenantB, creator, "B-1");

        ResponseEntity<String> response = get("/api/cases", TestTokens.jwt(subOf(email), email, List.of(a), false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode cases = json(response);
        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).path("providerCaseId").asText()).isEqualTo("A-1");
    }

    @Test
    void firstRequestCreatesAppUser() {
        String email = unique("Fresh") + "@Example.com";
        String sub = "kc-" + unique("new");
        String token = TestTokens.jwt(sub, email, null, false);
        assertThat(countAppUsers(sub)).isZero();

        ResponseEntity<String> first = get("/api/me", token);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countAppUsers(sub)).isEqualTo(1);
        assertThat(json(first).path("user").path("email").asText()).isEqualTo(email.toLowerCase());

        assertThat(get("/api/me", token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countAppUsers(sub)).isEqualTo(1);
    }

    private static UUID appUserId(String sub) throws Exception {
        try (Connection c = PostgresSupport.appConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM app_users WHERE keycloak_user_id = ?")) {
            ps.setString(1, sub);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static void insertCase(UUID tenantId, UUID createdBy, String providerCaseId) throws Exception {
        try (Connection c = PostgresSupport.appConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement set = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                set.setString(1, tenantId.toString());
                set.execute();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO dispute_cases (id, provider, provider_case_id, order_id, reason, amount, currency,
                        deadline_utc, status, created_utc, tenant_id, created_by)
                    VALUES (?, 'Banka', ?, 'ORD-1', 'Fraud', 10.00, 'TRY', now() + interval '2 days', 'Open', now(), ?, ?)
                    """)) {
                ps.setObject(1, UUID.randomUUID());
                ps.setString(2, providerCaseId);
                ps.setObject(3, tenantId);
                ps.setObject(4, createdBy);
                ps.executeUpdate();
            }
            c.commit();
        }
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return JSON.readTree(response.getBody());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response.getBody(), e);
        }
    }
}
