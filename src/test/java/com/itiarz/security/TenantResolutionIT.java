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
import com.itiarz.support.IntegrationTest.ProbeController;
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
        assertThat(body.path("code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void methodSecurityDenialReturns403ProblemJson() throws Exception {
        String email = unique("viewer") + "@example.com";
        String acme = unique("acme");
        seedTenant(acme, "ACTIVE");
        seedMember(acme, email, "VIEWER", "ACTIVE");

        ResponseEntity<String> response = get(ProbeController.MEMBER_MANAGE_PATH,
                TestTokens.jwt(subOf(email), email, List.of(acme), false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertProblemJson(response);
        assertThat(json(response).path("code").asText()).isEqualTo("FORBIDDEN");
    }

    @Test
    void platformAdminWithoutOrgGetsPlatformPermissionsOnly() throws Exception {
        String email = unique("platform") + "@example.com";
        String token = TestTokens.jwt(subOf(email), email, null, true);

        ResponseEntity<String> me = get("/api/me", token);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(me);
        assertThat(body.path("platformAdmin").asBoolean()).isTrue();
        assertThat(body.path("activeTenant").isNull()).isTrue();
        assertThat(texts(body.path("permissions"))).containsExactlyInAnyOrder(
                "tenant:create", "tenant:suspend", "tenant:invite-admin", "platform-audit:read");

        ResponseEntity<String> cases = get("/api/cases", token);
        assertThat(cases.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(cases).path("code").asText()).isEqualTo("TENANT_NOT_SELECTED");
    }

    @Test
    void platformAdminNeverGetsTenantContextEvenWithMembership() throws Exception {
        String email = unique("platform-member") + "@example.com";
        String acme = unique("acme");
        seedTenant(acme, "ACTIVE");
        seedMember(acme, email, "TENANT_ADMIN", "ACTIVE");
        String token = TestTokens.jwt(subOf(email), email, List.of(acme), true);

        ResponseEntity<String> cases = get("/api/cases", token);
        assertThat(cases.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(cases).path("code").asText()).isEqualTo("TENANT_NOT_SELECTED");

        JsonNode me = json(get("/api/me", token));
        assertThat(me.path("activeTenant").isNull()).isTrue();
        assertThat(texts(me.path("permissions"))).doesNotContain("case:read", "member:manage");
    }

    @Test
    void tenantlessCreateCaseReturns403() throws Exception {
        String email = unique("creator") + "@example.com";

        ResponseEntity<String> response = postJson("/api/cases", TestTokens.jwt(subOf(email), email, null, false), """
                {"provider":"Banka","providerCaseId":"X-1","orderId":"O-1","reason":"Fraud",
                 "amount":10.00,"currency":"TRY","deadlineUtc":"2099-01-01T00:00:00Z"}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(response).path("code").asText()).isEqualTo("TENANT_NOT_SELECTED");
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
        assertProblemJson(response);
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

    @Test
    void unverifiedEmailClaimDoesNotOverwriteStoredEmail() {
        String email = unique("kalici") + "@example.com";
        String sub = "kc-" + unique("verified");
        assertThat(get("/api/me", TestTokens.jwt(sub, email, null, false)).getStatusCode()).isEqualTo(HttpStatus.OK);

        String other = unique("baskasi") + "@example.com";
        String unverified = TestTokens.sign(TestTokens.claims(sub, other, null, false)
                .claim("email_verified", false).build());
        ResponseEntity<String> response = get("/api/me", unverified);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).path("user").path("email").asText()).isEqualTo(email);

        String verified = TestTokens.jwt(sub, other, null, false);
        assertThat(json(get("/api/me", verified)).path("user").path("email").asText()).isEqualTo(other);
    }

    private static void assertProblemJson(ResponseEntity<String> response) {
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
    }

    private static List<String> texts(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(node -> result.add(node.asText()));
        return result;
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
