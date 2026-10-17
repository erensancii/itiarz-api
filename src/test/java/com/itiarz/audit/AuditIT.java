package com.itiarz.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itiarz.common.TraceIdFilter;
import com.itiarz.security.RequestContext;
import com.itiarz.security.TenantRole;
import com.itiarz.support.IntegrationTest;
import com.itiarz.support.PostgresSupport;
import com.itiarz.support.TestTokens;

class AuditIT extends IntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    AuditService auditService;
    @Autowired
    TransactionTemplate transactionTemplate;

    @Test
    void adminCanReadOwnTenantAudit() throws Exception {
        String email = unique("admin") + "@example.com";
        String slug = unique("acme");
        UUID tenant = seedTenant(slug, "ACTIVE");
        seedMember(slug, email, "TENANT_ADMIN", "ACTIVE");
        UUID actor = appUserId(subOf(email));
        insertAudit(tenant, actor, "case.created", "case", "c-1", "{\"provider\":\"Banka\"}");

        ResponseEntity<String> response = get("/api/audit?page=0&size=50",
                TestTokens.jwt(subOf(email), email, List.of(slug), false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.path("page").asInt()).isZero();
        assertThat(body.path("size").asInt()).isEqualTo(50);
        assertThat(body.path("total").asLong()).isEqualTo(1);
        JsonNode item = body.path("items").get(0);
        assertThat(item.path("action").asText()).isEqualTo("case.created");
        assertThat(item.path("actor").path("email").asText()).isEqualTo(email);
        assertThat(item.path("targetType").asText()).isEqualTo("case");
        assertThat(item.path("targetId").asText()).isEqualTo("c-1");
        assertThat(item.path("details").path("provider").asText()).isEqualTo("Banka");
        assertThat(item.path("occurredAt").asText()).isNotBlank();
    }

    @Test
    void analystGets403OnAudit() {
        String email = unique("analyst") + "@example.com";
        String slug = unique("acme");
        seedTenant(slug, "ACTIVE");
        seedMember(slug, email, "ANALYST", "ACTIVE");

        ResponseEntity<String> response = get("/api/audit", TestTokens.jwt(subOf(email), email, List.of(slug), false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void auditOnlyShowsOwnTenant() throws Exception {
        String email = unique("admin") + "@example.com";
        String a = unique("acme");
        String b = unique("globex");
        UUID tenantA = seedTenant(a, "ACTIVE");
        UUID tenantB = seedTenant(b, "ACTIVE");
        seedMember(a, email, "TENANT_ADMIN", "ACTIVE");
        insertAudit(tenantA, null, "case.created", "case", "a-1", null);
        insertAudit(tenantB, null, "case.transition", "case", "b-1", null);

        JsonNode body = JSON.readTree(
                get("/api/audit", TestTokens.jwt(subOf(email), email, List.of(a), false)).getBody());

        assertThat(body.path("total").asLong()).isEqualTo(1);
        assertThat(body.path("items")).hasSize(1);
        assertThat(body.path("items").get(0).path("targetId").asText()).isEqualTo("a-1");
        assertThat(body.path("items").get(0).path("actor").isNull()).isTrue();
    }

    @Test
    void sizeAbove200IsClamped() throws Exception {
        String email = unique("admin") + "@example.com";
        String slug = unique("acme");
        seedTenant(slug, "ACTIVE");
        seedMember(slug, email, "TENANT_ADMIN", "ACTIVE");

        JsonNode body = JSON.readTree(get("/api/audit?size=1000",
                TestTokens.jwt(subOf(email), email, List.of(slug), false)).getBody());

        assertThat(body.path("size").asInt()).isEqualTo(200);
    }

    @Test
    void recordPersistsContextDetailsIpAndTrace() throws Exception {
        String email = unique("admin") + "@example.com";
        String slug = unique("acme");
        UUID tenant = seedTenant(slug, "ACTIVE");
        seedMember(slug, email, "TENANT_ADMIN", "ACTIVE");
        UUID user = appUserId(subOf(email));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.setAttribute(TraceIdFilter.ATTRIBUTE, "trace-xyz");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        com.itiarz.security.RequestContextHolder.set(
                new RequestContext(user, subOf(email), email, false, tenant, slug, TenantRole.TENANT_ADMIN));
        try {
            transactionTemplate.executeWithoutResult(s ->
                    auditService.record(AuditActions.CASE_ASSIGNED, "case", "c-9", Map.of("to", "x")));
        } finally {
            com.itiarz.security.RequestContextHolder.clear();
            RequestContextHolder.resetRequestAttributes();
        }

        try (Connection c = PostgresSupport.ownerConnection()) {
            setLocal(c, "app.tenant_id", tenant.toString());
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT host(ip), trace_id, actor_user_id, details->>'to' FROM audit_events");
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("203.0.113.7");
                assertThat(rs.getString(2)).isEqualTo("trace-xyz");
                assertThat(rs.getObject(3, UUID.class)).isEqualTo(user);
                assertThat(rs.getString(4)).isEqualTo("x");
            }
        }
    }

    @Test
    void platformRecordsAreWrittenWithNullAndExplicitTenant() throws Exception {
        String slug = unique("acme");
        UUID tenant = seedTenant(slug, "ACTIVE");
        com.itiarz.security.RequestContextHolder.runAsPlatform(() -> {
            transactionTemplate.executeWithoutResult(s -> {
                auditService.recordPlatform(AuditActions.TENANT_CREATED, "tenant", slug, null);
                auditService.recordForTenant(tenant, AuditActions.MEMBER_INVITED, "member", "m-1", Map.of("k", 1));
            });
            return null;
        });

        try (Connection c = PostgresSupport.ownerConnection()) {
            setLocal(c, "app.platform", "on");
            assertThat(count(c, "SELECT count(*) FROM audit_events WHERE target_id = '" + slug + "'")).isEqualTo(1);
        }
        try (Connection c = PostgresSupport.ownerConnection()) {
            setLocal(c, "app.tenant_id", tenant.toString());
            assertThat(count(c, "SELECT count(*) FROM audit_events WHERE action = 'member.invited'")).isEqualTo(1);
        }
    }

    private static void setLocal(Connection c, String key, String value) throws Exception {
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config(?, ?, true)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.execute();
        }
    }

    private static long count(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void paginationReturnsRequestedPage() throws Exception {
        String email = unique("admin") + "@example.com";
        String slug = unique("acme");
        UUID tenant = seedTenant(slug, "ACTIVE");
        seedMember(slug, email, "TENANT_ADMIN", "ACTIVE");
        for (int i = 0; i < 3; i++) {
            insertAudit(tenant, null, "case.created", "case", "p-" + i, null);
        }
        String token = TestTokens.jwt(subOf(email), email, List.of(slug), false);

        JsonNode body = JSON.readTree(get("/api/audit?page=1&size=2", token).getBody());
        assertThat(body.path("items")).hasSize(1);
        assertThat(body.path("total").asLong()).isEqualTo(3);
        assertThat(body.path("page").asInt()).isEqualTo(1);
        assertThat(body.path("size").asInt()).isEqualTo(2);

        JsonNode clamped = JSON.readTree(get("/api/audit?page=-1&size=0", token).getBody());
        assertThat(clamped.path("page").asInt()).isZero();
        assertThat(clamped.path("size").asInt()).isEqualTo(1);
        assertThat(clamped.path("items")).hasSize(1);
    }

    @Test
    void platformAdminWithoutOrgGets403OnAudit() throws Exception {
        String email = unique("platform") + "@example.com";

        ResponseEntity<String> response = get("/api/audit", TestTokens.jwt(subOf(email), email, null, true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(
                org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
    }

    @Test
    void forwardedForHeaderIsIgnored() throws Exception {
        String email = unique("admin") + "@example.com";
        String slug = unique("acme");
        UUID tenant = seedTenant(slug, "ACTIVE");
        seedMember(slug, email, "TENANT_ADMIN", "ACTIVE");
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(TestTokens.jwt(subOf(email), email, List.of(slug), false));
        headers.add("X-Forwarded-For", "203.0.113.9");

        ResponseEntity<String> response = rest.exchange(AuditProbeController.PATH, org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);

        try (Connection c = PostgresSupport.ownerConnection()) {
            setLocal(c, "app.tenant_id", tenant.toString());
            try (PreparedStatement ps = c.prepareStatement("SELECT host(ip) FROM audit_events");
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isNotNull().isNotEqualTo("203.0.113.9");
            }
        }
    }


    @Test
    void recordWithoutTransactionIsRejected() {
        assertThatThrownBy(() -> auditService.recordPlatform(AuditActions.TENANT_CREATED, "tenant", "x", null))
                .isInstanceOf(IllegalTransactionStateException.class);
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

    private static void insertAudit(UUID tenantId, UUID actor, String action, String targetType, String targetId,
                                    String details) throws Exception {
        try (Connection c = PostgresSupport.appConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement set = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                set.setString(1, tenantId.toString());
                set.execute();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO audit_events (id, tenant_id, actor_user_id, "
                    + "action, target_type, target_id, details) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, tenantId);
                ps.setObject(3, actor);
                ps.setString(4, action);
                ps.setString(5, targetType);
                ps.setString(6, targetId);
                ps.setString(7, details);
                ps.executeUpdate();
            }
            c.commit();
        }
    }
}
