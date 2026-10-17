package com.itiarz.cases;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itiarz.support.IntegrationTest;
import com.itiarz.support.PostgresSupport;
import com.itiarz.support.TestTokens;

/** Shared fixtures for the case API integration tests. */
abstract class CaseApiSupport extends IntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();

    /** A tenant member: e-mail, app user id and a token selecting the tenant. */
    record Member(String email, UUID userId, String token) { }

    Member member(String slug, String role) {
        return member(slug, role, "ACTIVE");
    }

    Member member(String slug, String role, String status) {
        String email = unique(role.toLowerCase()) + "@example.com";
        seedMember(slug, email, role, status);
        return new Member(email, appUserId(subOf(email)),
                TestTokens.jwt(subOf(email), email, List.of(slug), false));
    }

    ResponseEntity<String> create(String token, String providerCaseId, String reason) {
        return postJson("/api/cases", token, """
                {"provider":"Banka","providerCaseId":"%s","orderId":"ORD-1","reason":"%s",
                 "amount":125.50,"currency":"TRY","deadlineUtc":"2099-01-01T00:00:00Z"}
                """.formatted(providerCaseId, reason));
    }

    /** Creates a Fraud case and returns its detail. */
    JsonNode createCase(String token) {
        ResponseEntity<String> response = create(token, unique("CB"), "Fraud");
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return json(response);
    }

    ResponseEntity<String> addEvidence(String token, String caseId, String kind) {
        return postJson("/api/cases/" + caseId + "/evidence", token,
                "{\"kind\":\"%s\",\"note\":\"not\"}".formatted(kind));
    }

    ResponseEntity<String> transition(String token, String caseId, String to, String reason, long version) {
        String reasonJson = reason == null ? "" : ",\"reason\":\"%s\"".formatted(reason);
        return postJson("/api/cases/" + caseId + "/transitions", token,
                "{\"to\":\"%s\",\"version\":%d%s}".formatted(to, version, reasonJson));
    }

    ResponseEntity<String> assign(String token, String caseId, UUID userId) {
        String value = userId == null ? "null" : "\"" + userId + "\"";
        return patchJson("/api/cases/" + caseId + "/assignee", token, "{\"userId\":" + value + "}");
    }

    /** Fraud case with complete evidence, moved to Ready by {@code preparer}; returns the detail. */
    JsonNode readyCase(Member preparer) {
        String id = createCase(preparer.token()).path("id").asText();
        ok(addEvidence(preparer.token(), id, "PaymentRecord"));
        JsonNode afterEvidence = ok(addEvidence(preparer.token(), id, "3DSRecord"));
        return ok(transition(preparer.token(), id, "Ready", null, afterEvidence.path("version").asLong()));
    }

    /** Ready case prepared by {@code preparer} and submitted by {@code approver}. */
    JsonNode submittedCase(Member preparer, Member approver) {
        JsonNode ready = readyCase(preparer);
        return ok(transition(approver.token(), ready.path("id").asText(), "Submitted", null,
                ready.path("version").asLong()));
    }

    static JsonNode ok(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return json(response);
    }

    static void assertProblem(ResponseEntity<String> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        assertThat(json(response).path("code").asText()).isEqualTo(code);
    }

    static JsonNode json(ResponseEntity<String> response) {
        try {
            return JSON.readTree(response.getBody());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response.getBody(), e);
        }
    }

    static UUID appUserId(String sub) {
        try (Connection c = PostgresSupport.appConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM app_users WHERE keycloak_user_id = ?")) {
            ps.setString(1, sub);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, UUID.class) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code select count(*)} as the owner role inside {@code tenantId} (RLS is forced for the owner too). */
    static long countInTenant(UUID tenantId, String sql, Object... args) {
        try (Connection c = PostgresSupport.ownerConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement set = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                set.setString(1, tenantId.toString());
                set.execute();
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
