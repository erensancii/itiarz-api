package com.itiarz.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itiarz.support.IntegrationTest;
import com.itiarz.support.TestTokens;

/** Malformed path ids and query parameters are client errors (problem+json), never a logged 500. */
class TypeMismatchProblemIT extends IntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private String token;

    @BeforeEach
    void tenantAdmin() {
        String slug = unique("acme");
        String email = unique("admin") + "@example.com";
        seedTenant(slug, "ACTIVE");
        seedMember(slug, email, "TENANT_ADMIN", "ACTIVE");
        token = TestTokens.jwt(subOf(email), email, List.of(slug), false);
    }

    @Test
    void malformedPathIdIs404NotFound() throws Exception {
        ResponseEntity<String> response = get("/api/cases/abc", token);

        assertProblem(response, HttpStatus.NOT_FOUND);
        assertThat(JSON.readTree(response.getBody()).path("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void malformedQueryParameterIs400ValidationWithField() throws Exception {
        ResponseEntity<String> response = get("/api/audit?page=x", token);

        assertProblem(response, HttpStatus.BAD_REQUEST);
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION");
        assertThat(body.path("traceId").asText()).isNotBlank();
        assertThat(body.path("errors").get(0).path("field").asText()).isEqualTo("page");
        assertThat(body.path("errors").get(0).path("message").asText()).isNotBlank();
    }

    private static void assertProblem(ResponseEntity<String> response, HttpStatus status) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
    }
}
