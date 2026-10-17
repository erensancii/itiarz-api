package com.itiarz.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itiarz.keycloak.KeycloakUnavailableException;
import com.itiarz.support.FakeKeycloakGateway.Invitation;
import com.itiarz.support.IntegrationTest;
import com.itiarz.support.PostgresSupport;
import com.itiarz.support.TestTokens;

class PlatformTenantIT extends IntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SLUG_MESSAGE = "Yalnızca küçük harf, rakam ve tire kullanın (3–63 karakter).";

    private String platformToken;

    @BeforeEach
    void platformAdmin() {
        String email = unique("root") + "@itiarz.local";
        platformToken = TestTokens.jwt("kc-platform-" + UUID.randomUUID(), email, List.of(), true);
    }

    @Test
    void platformAdminCreatesTenant() {
        String slug = unique("acme");

        ResponseEntity<String> response = create("Acme Ödeme A.Ş.", slug, "Yonetici@Acme.LOCAL");

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = json(response);
        UUID tenantId = UUID.fromString(body.path("id").asText());
        assertThat(response.getHeaders().getLocation()).hasToString("/api/platform/tenants/" + tenantId);
        assertThat(body.path("slug").asText()).isEqualTo(slug);
        assertThat(body.path("name").asText()).isEqualTo("Acme Ödeme A.Ş.");
        assertThat(body.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.path("inviteSent").asBoolean()).isTrue();
        assertThat(body.path("createdAt").asText()).isNotBlank();

        String orgId = fakeKeycloak.findOrganizationIdByAlias(slug).orElseThrow();
        assertThat(query(null, "SELECT keycloak_org_id FROM tenants WHERE id = ?::uuid", tenantId))
                .isEqualTo(orgId);
        assertThat(query(null, "SELECT status FROM tenants WHERE id = ?::uuid", tenantId)).isEqualTo("ACTIVE");
        assertThat(query(tenantId, "SELECT role || '/' || status FROM memberships WHERE invited_email = ?",
                "yonetici@acme.local")).isEqualTo("TENANT_ADMIN/INVITED");
        assertThat(fakeKeycloak.invitations()).containsExactly(new Invitation(orgId, "yonetici@acme.local"));

        // tenant.created is a platform event (tenant_id NULL) targeting the tenant
        assertThat(platformAudit("tenant.created", tenantId)).isEqualTo(1);
        // member.invited belongs to the new tenant
        assertThat(query(tenantId, "SELECT count(*)::text FROM audit_events WHERE action = ? AND tenant_id IS NOT NULL",
                "member.invited")).isEqualTo("1");
    }

    @Test
    void slugValidation() {
        for (String slug : List.of("çiçek-market", "Acme", "a", "-acme")) {
            ResponseEntity<String> response = create("Firma", slug, "admin@example.com");

            assertProblem(response, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION");
            JsonNode errors = json(response).path("errors");
            assertThat(errors.get(0).path("field").asText()).as(slug).isEqualTo("slug");
            assertThat(errors.get(0).path("message").asText()).as(slug).isEqualTo(SLUG_MESSAGE);
        }
        assertThat(fakeKeycloak.organizationCount()).isZero();
    }

    @Test
    void invalidNameAndEmailAre422() {
        ResponseEntity<String> noName = create("", unique("acme"), "admin@example.com");
        assertProblem(noName, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION");
        assertThat(json(noName).path("errors").findValuesAsText("field")).containsExactly("name");

        ResponseEntity<String> badEmail = create("Firma", unique("acme"), "not-an-email");
        assertProblem(badEmail, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION");
        assertThat(json(badEmail).path("errors").findValuesAsText("field")).containsExactly("adminEmail");
        assertThat(fakeKeycloak.organizationCount()).isZero();
    }

    @Test
    void duplicateSlugIs409() {
        String slug = unique("acme");
        seedTenant(slug, "ACTIVE");

        assertProblem(create("Acme", slug, "admin@example.com"), HttpStatus.CONFLICT, "CONFLICT");
        assertThat(fakeKeycloak.organizationCount()).isZero();

        // an organization that exists only in Keycloak (alias conflict) is a 409 as well
        String other = unique("acme");
        fakeKeycloak.createOrganization("Elsewhere", other);
        assertProblem(create("Acme", other, "admin@example.com"), HttpStatus.CONFLICT, "CONFLICT");
        assertThat(query(null, "SELECT count(*)::text FROM tenants WHERE slug = ?", other)).isEqualTo("0");
        assertThat(fakeKeycloak.invitations()).isEmpty();
    }

    @Test
    void dbFailureDeletesKeycloakOrganization() {
        String takenOrgId = UUID.randomUUID().toString();
        seedTenant(unique("taken"), "ACTIVE", takenOrgId);
        fakeKeycloak.nextOrganizationId(takenOrgId); // the tenant insert hits uq keycloak_org_id
        String slug = unique("acme");

        ResponseEntity<String> response = create("Acme", slug, "admin@example.com");

        // the original error keeps its mapping (unique violation → 409 CONFLICT)
        assertProblem(response, HttpStatus.CONFLICT, "CONFLICT");
        assertThat(fakeKeycloak.deletedOrganizations()).containsExactly(takenOrgId);
        assertThat(fakeKeycloak.findOrganizationIdByAlias(slug)).isEmpty();
        assertThat(query(null, "SELECT count(*)::text FROM tenants WHERE slug = ?", slug)).isEqualTo("0");
        assertThat(fakeKeycloak.invitations()).isEmpty();
    }

    @Test
    void inviteFailureKeepsTenantAndAudits() {
        String slug = unique("acme");
        // createOrganization succeeds, the invitation after commit fails
        String orgId = UUID.randomUUID().toString();
        fakeKeycloak.nextOrganizationId(orgId);
        fakeKeycloak.failNextInvitation(new KeycloakUnavailableException("invite down", null));

        ResponseEntity<String> response = create("Acme", slug, "admin@example.com");

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = json(response);
        assertThat(body.path("inviteSent").asBoolean(true)).isFalse();
        UUID tenantId = UUID.fromString(body.path("id").asText());
        assertThat(query(null, "SELECT status FROM tenants WHERE id = ?::uuid", tenantId)).isEqualTo("ACTIVE");
        assertThat(query(tenantId, "SELECT status FROM memberships WHERE invited_email = ?", "admin@example.com"))
                .isEqualTo("INVITED");
        assertThat(fakeKeycloak.findOrganizationIdByAlias(slug)).contains(orgId);
        assertThat(fakeKeycloak.deletedOrganizations()).isEmpty();
        assertThat(platformAudit("tenant.invite_failed", tenantId)).isEqualTo(1);
        assertThat(platformAudit("tenant.created", tenantId)).isEqualTo(1);

        // "tekrar gönder": the platform invitation endpoint re-sends the pending invitation
        ResponseEntity<String> resend = postJson("/api/platform/tenants/" + tenantId + "/invitations",
                platformToken, "{\"email\":\"admin@example.com\"}");
        assertThat(resend.getStatusCode()).as(resend.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(fakeKeycloak.invitations()).containsExactly(new Invitation(orgId, "admin@example.com"));
    }

    @Test
    void suspendBlocksTenantUsers() {
        String slug = unique("acme");
        UUID tenantId = seedTenant(slug, "ACTIVE");
        String analyst = unique("analyst") + "@example.com";
        seedMember(slug, analyst, "ANALYST", "ACTIVE");
        String analystToken = TestTokens.jwt(subOf(analyst), analyst, List.of(slug), false);
        assertThat(get("/api/cases", analystToken).getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode suspended = ok(postJson("/api/platform/tenants/" + tenantId + "/suspend", platformToken, ""));
        assertThat(suspended.path("status").asText()).isEqualTo("SUSPENDED");
        assertProblem(get("/api/cases", analystToken), HttpStatus.FORBIDDEN, "TENANT_SUSPENDED");
        // idempotent
        assertThat(ok(postJson("/api/platform/tenants/" + tenantId + "/suspend", platformToken, ""))
                .path("status").asText()).isEqualTo("SUSPENDED");
        assertThat(platformAudit("tenant.suspended", tenantId)).isEqualTo(1);

        JsonNode activated = ok(postJson("/api/platform/tenants/" + tenantId + "/activate", platformToken, ""));
        assertThat(activated.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(get("/api/cases", analystToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(platformAudit("tenant.activated", tenantId)).isEqualTo(1);

        assertProblem(postJson("/api/platform/tenants/" + UUID.randomUUID() + "/suspend", platformToken, ""),
                HttpStatus.NOT_FOUND, "NOT_FOUND");
    }

    @Test
    void listsTenantsWithMemberCountOnly() {
        String slug = unique("acme");
        UUID tenantId = seedTenant(slug, "ACTIVE");
        seedMember(slug, unique("a") + "@example.com", "TENANT_ADMIN", "ACTIVE");
        seedMember(slug, unique("b") + "@example.com", "ANALYST", "INVITED");
        seedMember(slug, unique("c") + "@example.com", "VIEWER", "REMOVED");

        ResponseEntity<String> response = get("/api/platform/tenants", platformToken);

        JsonNode list = ok(response);
        JsonNode row = null;
        for (JsonNode node : list) {
            if (node.path("id").asText().equals(tenantId.toString())) {
                row = node;
            }
        }
        assertThat(row).as(response.getBody()).isNotNull();
        assertThat(row.path("slug").asText()).isEqualTo(slug);
        assertThat(row.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(row.path("memberCount").asLong()).isEqualTo(2);
        assertThat(row.path("createdAt").asText()).isNotBlank();
        List<String> fields = new java.util.ArrayList<>();
        row.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "slug", "name", "status", "memberCount", "createdAt");
    }

    @Test
    void platformInvitesTenantAdmin() {
        String slug = unique("acme");
        String orgId = fakeKeycloak.createOrganization("Acme", slug);
        UUID tenantId = seedTenant(slug, "ACTIVE", orgId);
        String path = "/api/platform/tenants/" + tenantId + "/invitations";

        ResponseEntity<String> created = postJson(path, platformToken, "{\"email\":\"Yeni.Admin@Example.com\"}");

        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = json(created);
        assertThat(body.path("email").asText()).isEqualTo("yeni.admin@example.com");
        assertThat(body.path("role").asText()).isEqualTo("TENANT_ADMIN");
        assertThat(body.path("status").asText()).isEqualTo("INVITED");
        assertThat(fakeKeycloak.invitations()).containsExactly(new Invitation(orgId, "yeni.admin@example.com"));

        // a pending invitation (even with another role) is re-sent as TENANT_ADMIN, not a 409
        String pending = unique("pending") + "@example.com";
        UUID pendingId = seedMember(slug, pending, "ANALYST", "INVITED");
        ResponseEntity<String> resent = postJson(path, platformToken, "{\"email\":\"" + pending + "\"}");
        assertThat(resent.getStatusCode()).as(resent.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(resent).path("id").asText()).isEqualTo(pendingId.toString());
        assertThat(json(resent).path("role").asText()).isEqualTo("TENANT_ADMIN");
        assertThat(fakeKeycloak.invitations()).contains(new Invitation(orgId, pending));
        assertThat(query(tenantId, "SELECT count(*)::text FROM audit_events WHERE action = ?", "member.invited"))
                .isEqualTo("2");

        // Keycloak down for a new invitation: 502, nothing left behind
        String failing = unique("down") + "@example.com";
        fakeKeycloak.failNextCall(new KeycloakUnavailableException("down", null));
        assertProblem(postJson(path, platformToken, "{\"email\":\"" + failing + "\"}"),
                HttpStatus.BAD_GATEWAY, "KEYCLOAK_UNAVAILABLE");
        assertThat(query(tenantId, "SELECT count(*)::text FROM memberships WHERE invited_email = ?", failing))
                .isEqualTo("0");

        assertProblem(postJson("/api/platform/tenants/" + UUID.randomUUID() + "/invitations", platformToken,
                "{\"email\":\"x@example.com\"}"), HttpStatus.NOT_FOUND, "NOT_FOUND");
    }

    @Test
    void tenantAdminCannotUsePlatformEndpoints() {
        String slug = unique("acme");
        UUID tenantId = seedTenant(slug, "ACTIVE");
        String admin = unique("admin") + "@example.com";
        seedMember(slug, admin, "TENANT_ADMIN", "ACTIVE");
        String adminToken = TestTokens.jwt(subOf(admin), admin, List.of(slug), false);

        assertProblem(get("/api/platform/tenants", adminToken), HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertProblem(create("X", unique("x"), "x@example.com", adminToken), HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertProblem(postJson("/api/platform/tenants/" + tenantId + "/suspend", adminToken, ""),
                HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertProblem(postJson("/api/platform/tenants/" + tenantId + "/activate", adminToken, ""),
                HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertProblem(postJson("/api/platform/tenants/" + tenantId + "/invitations", adminToken,
                "{\"email\":\"x@example.com\"}"), HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertThat(query(null, "SELECT status FROM tenants WHERE id = ?::uuid", tenantId)).isEqualTo("ACTIVE");
    }

    @Test
    void platformAdminCannotReadCases() {
        assertThat(get("/api/cases", platformToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String withOrg = TestTokens.jwt("kc-platform-" + UUID.randomUUID(), unique("root") + "@itiarz.local",
                List.of("acme"), true);
        assertThat(get("/api/cases", withOrg).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- helpers -------------------------------------------------------------------------------

    private ResponseEntity<String> create(String name, String slug, String adminEmail) {
        return create(name, slug, adminEmail, platformToken);
    }

    private ResponseEntity<String> create(String name, String slug, String adminEmail, String token) {
        return postJson("/api/platform/tenants", token,
                "{\"name\":\"%s\",\"slug\":\"%s\",\"adminEmail\":\"%s\"}".formatted(name, slug, adminEmail));
    }

    private long platformAudit(String action, UUID tenantId) {
        return Long.parseLong(query(null,
                "SELECT count(*)::text FROM audit_events WHERE tenant_id IS NULL AND target_type = 'tenant' "
                        + "AND target_id = '" + tenantId + "' AND action = ?", action));
    }

    /**
     * First column of the first row (or null) as the owner role (RLS forced): inside {@code tenantId}
     * when given, else in platform mode.
     */
    private static String query(UUID tenantId, String sql, Object arg) {
        try (Connection c = PostgresSupport.ownerConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement set = c.prepareStatement(tenantId == null
                    ? "SELECT set_config('app.platform', 'on', true)"
                    : "SELECT set_config('app.tenant_id', '" + tenantId + "', true)")) {
                set.execute();
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, String.valueOf(arg));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode ok(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return json(response);
    }

    private static void assertProblem(ResponseEntity<String> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        assertThat(json(response).path("code").asText()).isEqualTo(code);
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return JSON.readTree(response.getBody());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response.getBody(), e);
        }
    }
}
