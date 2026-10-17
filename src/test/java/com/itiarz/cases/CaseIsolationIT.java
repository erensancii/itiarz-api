package com.itiarz.cases;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.JsonNode;

class CaseIsolationIT extends CaseApiSupport {

    @Test
    void otherTenantsCaseIsNotFoundForEveryEndpoint() {
        String a = unique("acme");
        String b = unique("globex");
        seedTenant(a, "ACTIVE");
        seedTenant(b, "ACTIVE");
        Member analystA = member(a, "ANALYST");
        Member analystB = member(b, "ANALYST");
        JsonNode caseB = createCase(analystB.token());
        String id = caseB.path("id").asText();
        long version = caseB.path("version").asLong();

        assertProblem(get("/api/cases/" + id, analystA.token()), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertProblem(addEvidence(analystA.token(), id, "PaymentRecord"), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertProblem(transition(analystA.token(), id, "InProgress", null, version), HttpStatus.NOT_FOUND,
                "NOT_FOUND");
        assertProblem(assign(analystA.token(), id, analystA.userId()), HttpStatus.NOT_FOUND, "NOT_FOUND");

        JsonNode listA = ok(get("/api/cases", analystA.token()));
        assertThat(listA).isEmpty();
        JsonNode unchanged = ok(get("/api/cases/" + id, analystB.token()));
        assertThat(unchanged.path("status").asText()).isEqualTo("Open");
        assertThat(unchanged.path("evidence")).isEmpty();
        assertThat(unchanged.path("assignee").isNull()).isTrue();
    }

    @Test
    void sameProviderReferenceIsUniquePerTenant() {
        String a = unique("acme");
        String b = unique("globex");
        seedTenant(a, "ACTIVE");
        seedTenant(b, "ACTIVE");
        Member analystA = member(a, "ANALYST");
        Member analystB = member(b, "ANALYST");
        String reference = unique("CB");

        assertThat(create(analystA.token(), reference, "Fraud").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create(analystB.token(), reference, "Fraud").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertProblem(create(analystA.token(), reference, "Other"), HttpStatus.CONFLICT, "DUPLICATE_CASE");
    }
}
