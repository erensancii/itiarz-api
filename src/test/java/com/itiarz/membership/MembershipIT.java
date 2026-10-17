package com.itiarz.membership;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

class MembershipIT extends IntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private String slug;
    private String orgId;
    private UUID tenantId;
    private String adminEmail;
    private String adminToken;
    private UUID adminMembershipId;

    @BeforeEach
    void tenantWithAdmin() {
        slug = unique("acme");
        orgId = fakeKeycloak.createOrganization("Acme " + slug, slug);
        tenantId = seedTenant(slug, "ACTIVE", orgId);
        adminEmail = unique("admin") + "@example.com";
        adminMembershipId = seedMember(slug, adminEmail, "TENANT_ADMIN", "ACTIVE");
        adminToken = token(adminEmail);
    }

    @Test
    void adminInvitesAnalyst() {
        String email = unique("analyst") + "@example.com";

        ResponseEntity<String> response = invite(adminToken, email, "ANALYST");

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = json(response);
        assertThat(body.path("id").asText()).isNotBlank();
        assertThat(body.path("email").asText()).isEqualTo(email);
        assertThat(body.path("role").asText()).isEqualTo("ANALYST");
        assertThat(body.path("status").asText()).isEqualTo("INVITED");
        assertThat(body.path("displayName").isNull()).isTrue();
        assertThat(fakeKeycloak.invitations()).contains(new Invitation(orgId, email));
        assertThat(countAudit("member.invited")).isEqualTo(1);

        JsonNode list = ok(get("/api/members", adminToken));
        assertThat(list.findValuesAsText("email")).contains(email, adminEmail);
        assertThat(body.has("userId")).isTrue();
        assertThat(body.path("userId").isNull()).as("pending invitation has no user yet").isTrue();
        for (JsonNode member : list) {
            if (member.path("email").asText().equals(adminEmail)) {
                assertThat(member.path("status").asText()).isEqualTo("ACTIVE");
                assertThat(UUID.fromString(member.path("userId").asText()))
                    .as("active member exposes the app user id used for case assignment").isNotNull();
            } else if (member.path("email").asText().equals(email)) {
                assertThat(member.path("userId").isNull()).isTrue();
            }
        }
    }

    @Test
    void inviteEmailIsNormalized() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            String local = unique("Admin.Yeni");
            String typed = local + "@Acme.LOCAL";
            String normalized = local.toLowerCase(Locale.ROOT) + "@acme.local";

            ResponseEntity<String> response = invite(adminToken, typed, "TENANT_ADMIN");

            assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
            assertThat(json(response).path("email").asText()).isEqualTo(normalized);
            assertThat(fakeKeycloak.invitations()).contains(new Invitation(orgId, normalized));

            String newcomer = TestTokens.jwt("kc-new-" + UUID.randomUUID(), normalized, List.of(slug), false);
            JsonNode me = ok(get("/api/me", newcomer));
            assertThat(me.path("activeTenant").path("role").asText()).isEqualTo("TENANT_ADMIN");
            assertThat(membershipStatus(normalized)).isEqualTo("ACTIVE");
            assertThat(countAudit("member.activated")).isEqualTo(1);

            // a dotted capital I would become a dotless 'ı' under the Turkish locale
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            ResponseEntity<String> capitalI = invite(adminToken, "IPEK-" + suffix + "@ACME.LOCAL", "VIEWER");
            assertThat(capitalI.getStatusCode()).as(capitalI.getBody()).isEqualTo(HttpStatus.CREATED);
            assertThat(json(capitalI).path("email").asText()).isEqualTo("ipek-" + suffix + "@acme.local");
            // ... and the token e-mail is normalized the same way, so the invitation activates
            String ipek = TestTokens.jwt("kc-ipek-" + suffix, "IPEK-" + suffix + "@ACME.LOCAL", List.of(slug), false);
            assertThat(ok(get("/api/me", ipek)).path("activeTenant").path("role").asText()).isEqualTo("VIEWER");
            assertThat(membershipStatus("ipek-" + suffix + "@acme.local")).isEqualTo("ACTIVE");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void duplicatePendingInviteIs409() {
        String email = unique("analyst") + "@example.com";
        assertThat(invite(adminToken, email, "ANALYST").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertProblem(invite(adminToken, email.toUpperCase(Locale.ROOT), "VIEWER"),
                HttpStatus.CONFLICT, "DUPLICATE_INVITE");
        // an active member's e-mail is a duplicate as well
        assertProblem(invite(adminToken, adminEmail, "VIEWER"), HttpStatus.CONFLICT, "DUPLICATE_INVITE");
        assertThat(fakeKeycloak.invitations()).hasSize(1);
    }

    @Test
    void reinvitingRemovedMemberReactivatesRow() {
        String email = unique("analyst") + "@example.com";
        UUID membershipId = seedMember(slug, email, "ANALYST", "ACTIVE");
        assertThat(delete("/api/members/" + membershipId, adminToken).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> response = invite(adminToken, email, "APPROVER");

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = json(response);
        assertThat(body.path("id").asText()).isEqualTo(membershipId.toString());
        assertThat(body.path("status").asText()).isEqualTo("INVITED");
        assertThat(body.path("role").asText()).isEqualTo("APPROVER");
    }

    @Test
    void unverifiedEmailDoesNotActivate() {
        String email = unique("analyst") + "@example.com";
        assertThat(invite(adminToken, email, "ANALYST").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String unverified = TestTokens.sign(TestTokens.claims("kc-unverified-" + UUID.randomUUID(), email,
                List.of(slug), false).claim("email_verified", false).build());
        ResponseEntity<String> response = get("/api/me", unverified);

        assertProblem(response, HttpStatus.FORBIDDEN, "NO_MEMBERSHIP");
        assertThat(membershipStatus(email)).isEqualTo("INVITED");
        assertThat(countAudit("member.activated")).isZero();
    }

    @Test
    void cannotRemoveLastAdmin() {
        assertProblem(delete("/api/members/" + adminMembershipId, adminToken), HttpStatus.CONFLICT, "LAST_ADMIN");
        assertThat(membershipStatus(adminEmail)).isEqualTo("ACTIVE");
    }

    @Test
    void cannotDemoteLastAdmin() {
        assertProblem(patchJson("/api/members/" + adminMembershipId, adminToken, "{\"role\":\"ANALYST\"}"),
                HttpStatus.CONFLICT, "LAST_ADMIN");
    }

    @Test
    void cannotChangeOwnRole() {
        seedMember(slug, unique("admin2") + "@example.com", "TENANT_ADMIN", "ACTIVE");

        assertProblem(patchJson("/api/members/" + adminMembershipId, adminToken, "{\"role\":\"ANALYST\"}"),
                HttpStatus.CONFLICT, "CONFLICT");
    }

    @Test
    void adminChangesAnotherMembersRole() {
        String email = unique("analyst") + "@example.com";
        UUID membershipId = seedMember(slug, email, "ANALYST", "ACTIVE");

        JsonNode body = ok(patchJson("/api/members/" + membershipId, adminToken, "{\"role\":\"APPROVER\"}"));

        assertThat(body.path("role").asText()).isEqualTo("APPROVER");
        assertThat(countAudit("member.role_changed")).isEqualTo(1);
        JsonNode me = ok(get("/api/me", token(email)));
        assertThat(me.path("activeTenant").path("role").asText()).isEqualTo("APPROVER");
    }

    @Test
    void removeAlsoRemovesFromKeycloakOrg() {
        String email = unique("analyst") + "@example.com";
        assertThat(invite(adminToken, email, "ANALYST").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String keycloakUserId = fakeKeycloak.registerUser(email);
        fakeKeycloak.addOrganizationMember(orgId, keycloakUserId);
        String memberToken = TestTokens.jwt(keycloakUserId, email, List.of(slug), false);
        assertThat(get("/api/me", memberToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        String membershipId = membershipId(email).toString();

        ResponseEntity<String> response = delete("/api/members/" + membershipId, adminToken);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(fakeKeycloak.membersOf(orgId)).doesNotContain(keycloakUserId);
        assertThat(membershipStatus(email)).isEqualTo("REMOVED");
        assertThat(countAudit("member.removed")).isEqualTo(1);
        assertProblem(get("/api/me", memberToken), HttpStatus.FORBIDDEN, "NO_MEMBERSHIP");
        assertThat(ok(get("/api/members", adminToken)).findValuesAsText("email")).doesNotContain(email);
    }

    @Test
    void keycloakDownOnRemoveKeepsMembership() {
        String email = unique("analyst") + "@example.com";
        assertThat(invite(adminToken, email, "ANALYST").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String keycloakUserId = fakeKeycloak.registerUser(email);
        fakeKeycloak.addOrganizationMember(orgId, keycloakUserId);
        assertThat(get("/api/me", TestTokens.jwt(keycloakUserId, email, List.of(slug), false)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        fakeKeycloak.failNextCall(new KeycloakUnavailableException("down", null));

        assertProblem(delete("/api/members/" + membershipId(email), adminToken),
                HttpStatus.BAD_GATEWAY, "KEYCLOAK_UNAVAILABLE");
        assertThat(membershipStatus(email)).isEqualTo("ACTIVE");
        assertThat(countAudit("member.removed")).isZero();
    }

    @Test
    void keycloakDownOnInviteReturns502AndNoRow() {
        String email = unique("analyst") + "@example.com";
        fakeKeycloak.failNextCall(new KeycloakUnavailableException("down", null));

        assertProblem(invite(adminToken, email, "ANALYST"), HttpStatus.BAD_GATEWAY, "KEYCLOAK_UNAVAILABLE");

        assertThat(membershipStatus(email)).isNull();
        assertThat(countAudit("member.invited")).isZero();
    }

    @Test
    void analystCannotInvite() {
        String email = unique("analyst") + "@example.com";
        seedMember(slug, email, "ANALYST", "ACTIVE");

        assertProblem(invite(token(email), unique("x") + "@example.com", "VIEWER"), HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertThat(get("/api/members", token(email)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void invalidInviteIs422WithFieldErrors() {
        ResponseEntity<String> badEmail = invite(adminToken, "not-an-email", "ANALYST");
        assertProblem(badEmail, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION");
        assertThat(json(badEmail).path("errors").findValuesAsText("field")).contains("email");

        ResponseEntity<String> noRole = postJson("/api/members/invitations", adminToken,
                "{\"email\":\"x@example.com\"}");
        assertProblem(noRole, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION");
        assertThat(json(noRole).path("errors").findValuesAsText("field")).contains("role");
    }

    @Test
    void removedOrForeignMembershipIs404() {
        String email = unique("gone") + "@example.com";
        UUID removed = seedMember(slug, email, "ANALYST", "REMOVED");
        String other = unique("other");
        seedTenant(other, "ACTIVE");
        // the admin is also a member of the other tenant: visible via the self-read policy, still 404
        UUID foreign = seedMember(other, adminEmail, "ANALYST", "ACTIVE");

        assertProblem(patchJson("/api/members/" + removed, adminToken, "{\"role\":\"VIEWER\"}"),
                HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertProblem(delete("/api/members/" + removed, adminToken), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertProblem(patchJson("/api/members/" + foreign, adminToken, "{\"role\":\"VIEWER\"}"),
                HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertProblem(delete("/api/members/" + foreign, adminToken), HttpStatus.NOT_FOUND, "NOT_FOUND");
    }

    @Test
    void invalidRoleValueIs422WithRoleField() {
        ResponseEntity<String> response = postJson("/api/members/invitations", adminToken,
                "{\"email\":\"x@example.com\",\"role\":\"BOSS\"}");

        assertProblem(response, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION");
        JsonNode errors = json(response).path("errors");
        assertThat(errors.findValuesAsText("field")).containsExactly("role");
        assertThat(errors.get(0).path("message").asText()).isNotBlank();
    }

    /**
     * Regression: a role change that read the member before a concurrent removal committed must not
     * write the old ACTIVE status back. The removal is simulated by a JDBC transaction that holds the
     * row lock; the PATCH has to wait for it and then see REMOVED (404).
     */
    @Test
    void roleChangeDoesNotResurrectConcurrentlyRemovedMember() throws Exception {
        String email = unique("analyst") + "@example.com";
        UUID membershipId = seedMember(slug, email, "ANALYST", "ACTIVE");

        ResponseEntity<String> response;
        try (Connection removal = tenantTransaction()) {
            try (PreparedStatement ps = removal.prepareStatement(
                    "UPDATE memberships SET status = 'REMOVED' WHERE id = ?")) {
                ps.setObject(1, membershipId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            CompletableFuture<ResponseEntity<String>> patch = CompletableFuture.supplyAsync(() ->
                    patchJson("/api/members/" + membershipId, adminToken, "{\"role\":\"APPROVER\"}"));
            awaitLockWaiter(patch);
            removal.commit();
            response = patch.get(30, TimeUnit.SECONDS);
        }

        assertProblem(response, HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertThat(membershipStatus(email)).isEqualTo("REMOVED");
    }

    /** Regression: two invitations of one e-mail racing on the unique key end in 409 DUPLICATE_INVITE. */
    @Test
    void concurrentDuplicateInviteIs409DuplicateInvite() throws Exception {
        String email = unique("analyst") + "@example.com";

        ResponseEntity<String> response;
        try (Connection other = tenantTransaction()) {
            try (PreparedStatement ps = other.prepareStatement(
                    "INSERT INTO memberships (id, tenant_id, invited_email, role, status) "
                            + "VALUES (?, ?, ?, 'VIEWER', 'INVITED')")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, tenantId);
                ps.setString(3, email);
                ps.executeUpdate();
            }
            CompletableFuture<ResponseEntity<String>> invite =
                    CompletableFuture.supplyAsync(() -> invite(adminToken, email, "ANALYST"));
            awaitLockWaiter(invite);
            other.commit();
            response = invite.get(30, TimeUnit.SECONDS);
        }

        assertProblem(response, HttpStatus.CONFLICT, "DUPLICATE_INVITE");
        assertThat(fakeKeycloak.invitations()).isEmpty();
    }

    /**
     * Regression: a user removed under an old e-mail who changed it and is invited under the new one
     * must be activated (the old row's user link is released), not locked out with a unique violation.
     */
    @Test
    void invitationUnderChangedEmailActivatesUserWithOldRemovedRow() {
        String oldEmail = unique("old") + "@example.com";
        UUID oldRow = seedMember(slug, oldEmail, "ANALYST", "REMOVED");
        String newEmail = unique("new") + "@example.com";
        assertThat(invite(adminToken, newEmail, "APPROVER").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // same Keycloak user (subject) as the removed row, now with the new e-mail
        String token = TestTokens.jwt(subOf(oldEmail), newEmail, List.of(slug), false);
        JsonNode me = ok(get("/api/me", token));

        assertThat(me.path("activeTenant").path("role").asText()).isEqualTo("APPROVER");
        assertThat(membershipStatus(newEmail)).isEqualTo("ACTIVE");
        assertThat(queryInTenant("SELECT user_id::text FROM memberships WHERE id = ?::uuid", oldRow.toString()))
                .isNull();
        assertThat(membershipStatus(oldEmail)).isEqualTo("REMOVED");
    }

    // --- helpers -------------------------------------------------------------------------------

    /** An open app-role transaction inside this test's tenant; the caller commits or closes (rollback). */
    private Connection tenantTransaction() throws Exception {
        Connection c = PostgresSupport.appConnection();
        c.setAutoCommit(false);
        try (PreparedStatement set = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            set.setString(1, tenantId.toString());
            set.execute();
        }
        return c;
    }

    /** Waits until some app-role session is blocked on a lock (the request under test). */
    private static void awaitLockWaiter(CompletableFuture<?> request) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        try (Connection c = PostgresSupport.appConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND usename = ?")) {
            ps.setString(1, PostgresSupport.APP_USER);
            while (System.nanoTime() < deadline) {
                if (request.isDone()) {
                    return; // finished without blocking: the assertions will tell what happened
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError("request never blocked on the row lock");
    }

    private String token(String email) {
        return TestTokens.jwt(subOf(email), email, List.of(slug), false);
    }

    private ResponseEntity<String> invite(String token, String email, String role) {
        return postJson("/api/members/invitations", token,
                "{\"email\":\"%s\",\"role\":\"%s\"}".formatted(email, role));
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

    private String membershipStatus(String email) {
        return queryInTenant("SELECT status FROM memberships WHERE invited_email = ?", email);
    }

    private UUID membershipId(String email) {
        return UUID.fromString(queryInTenant("SELECT id::text FROM memberships WHERE invited_email = ?", email));
    }

    private long countAudit(String action) {
        return Long.parseLong(queryInTenant("SELECT count(*)::text FROM audit_events WHERE action = ?", action));
    }

    /** First column of the first row (or null) as the owner role inside this test's tenant (RLS forced). */
    private String queryInTenant(String sql, String arg) {
        try (Connection c = PostgresSupport.ownerConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement set = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                set.setString(1, tenantId.toString());
                set.execute();
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, arg);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
