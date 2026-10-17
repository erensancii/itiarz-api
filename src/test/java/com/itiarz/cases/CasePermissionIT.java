package com.itiarz.cases;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.itiarz.support.TestTokens;

/** (endpoint, role) matrix for the case API. */
class CasePermissionIT extends CaseApiSupport {

    enum Op { LIST, GET, CREATE, EVIDENCE, START, ASSIGN, SUBMIT, CLOSE }

    static Stream<Arguments> matrix() {
        return Stream.of(
                Arguments.of(Op.LIST, "VIEWER", HttpStatus.OK),
                Arguments.of(Op.GET, "VIEWER", HttpStatus.OK),
                Arguments.of(Op.CREATE, "VIEWER", HttpStatus.FORBIDDEN),
                Arguments.of(Op.EVIDENCE, "VIEWER", HttpStatus.FORBIDDEN),
                Arguments.of(Op.START, "VIEWER", HttpStatus.FORBIDDEN),
                Arguments.of(Op.ASSIGN, "VIEWER", HttpStatus.FORBIDDEN),
                Arguments.of(Op.SUBMIT, "VIEWER", HttpStatus.FORBIDDEN),
                Arguments.of(Op.CLOSE, "VIEWER", HttpStatus.FORBIDDEN),
                Arguments.of(Op.CREATE, "ANALYST", HttpStatus.CREATED),
                Arguments.of(Op.EVIDENCE, "ANALYST", HttpStatus.OK),
                Arguments.of(Op.START, "ANALYST", HttpStatus.OK),
                Arguments.of(Op.ASSIGN, "ANALYST", HttpStatus.OK),
                Arguments.of(Op.SUBMIT, "ANALYST", HttpStatus.FORBIDDEN),
                Arguments.of(Op.CLOSE, "ANALYST", HttpStatus.FORBIDDEN),
                Arguments.of(Op.SUBMIT, "APPROVER", HttpStatus.OK),
                Arguments.of(Op.CLOSE, "APPROVER", HttpStatus.OK),
                Arguments.of(Op.SUBMIT, "TENANT_ADMIN", HttpStatus.OK),
                Arguments.of(Op.CLOSE, "TENANT_ADMIN", HttpStatus.OK));
    }

    @ParameterizedTest(name = "{0} as {1} -> {2}")
    @MethodSource("matrix")
    void endpointRoleMatrix(Op op, String role, HttpStatus expected) {
        String slug = unique("acme");
        seedTenant(slug, "ACTIVE");
        Member preparer = member(slug, "ANALYST");
        Member approver = member(slug, "APPROVER");
        Member caller = member(slug, role);

        ResponseEntity<String> response = call(op, caller, preparer, approver);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(expected);
        if (expected == HttpStatus.FORBIDDEN) {
            assertProblem(response, HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    @Test
    void platformUserWithoutOrganizationGets403() {
        String email = unique("platform") + "@example.com";

        ResponseEntity<String> response = get("/api/cases", TestTokens.jwt(subOf(email), email, null, true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
    }

    @Test
    void statusPatchEndpointIsGone() {
        String slug = unique("acme");
        seedTenant(slug, "ACTIVE");
        Member analyst = member(slug, "ANALYST");
        String id = createCase(analyst.token()).path("id").asText();

        ResponseEntity<String> response = patchJson("/api/cases/" + id + "/status", analyst.token(),
                "{\"status\":\"InProgress\"}");

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(ok(get("/api/cases/" + id, analyst.token())).path("status").asText()).isEqualTo("Open");
    }

    private ResponseEntity<String> call(Op op, Member caller, Member preparer, Member approver) {
        return switch (op) {
            case LIST -> {
                createCase(preparer.token());
                yield get("/api/cases", caller.token());
            }
            case GET -> get("/api/cases/" + createCase(preparer.token()).path("id").asText(), caller.token());
            case CREATE -> create(caller.token(), unique("CB"), "Fraud");
            case EVIDENCE -> addEvidence(caller.token(), createCase(preparer.token()).path("id").asText(),
                    "PaymentRecord");
            case START -> {
                JsonNode created = createCase(preparer.token());
                yield transition(caller.token(), created.path("id").asText(), "InProgress", null,
                        created.path("version").asLong());
            }
            case ASSIGN -> assign(caller.token(), createCase(preparer.token()).path("id").asText(), preparer.userId());
            case SUBMIT -> {
                JsonNode ready = readyCase(preparer);
                yield transition(caller.token(), ready.path("id").asText(), "Submitted", null,
                        ready.path("version").asLong());
            }
            case CLOSE -> {
                JsonNode submitted = submittedCase(preparer, approver);
                yield transition(caller.token(), submitted.path("id").asText(), "Won", null,
                        submitted.path("version").asLong());
            }
        };
    }
}
