package com.itiarz.cases;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

class CaseWorkflowIT extends CaseApiSupport {

    String slug;
    UUID tenantId;
    Member analyst;
    Member approver;

    @BeforeEach
    void tenant() {
        slug = unique("acme");
        tenantId = seedTenant(slug, "ACTIVE");
        analyst = member(slug, "ANALYST");
        approver = member(slug, "APPROVER");
    }

    @Test
    void analystPreparesApproverSubmits() {
        JsonNode ready = readyCase(analyst);
        String id = ready.path("id").asText();
        assertThat(ready.path("status").asText()).isEqualTo("Ready");
        assertThat(ready.path("preparedBy").path("id").asText()).isEqualTo(analyst.userId().toString());
        assertThat(ready.path("preparedBy").path("email").asText()).isEqualTo(analyst.email());
        assertThat(ready.path("missing")).isEmpty();

        JsonNode submitted = ok(transition(approver.token(), id, "Submitted", null, ready.path("version").asLong()));

        assertThat(submitted.path("status").asText()).isEqualTo("Submitted");
        assertThat(submitted.path("approvedBy").path("id").asText()).isEqualTo(approver.userId().toString());
        assertThat(submitted.path("version").asLong()).isGreaterThan(ready.path("version").asLong());
        JsonNode transitions = submitted.path("transitions");
        JsonNode last = transitions.get(transitions.size() - 1);
        assertThat(last.path("from").asText()).isEqualTo("Ready");
        assertThat(last.path("to").asText()).isEqualTo("Submitted");
        assertThat(last.path("actor").path("email").asText()).isEqualTo(approver.email());
        assertThat(last.path("occurredAt").asText()).isNotBlank();
        UUID caseId = UUID.fromString(id);
        assertThat(countInTenant(tenantId, "SELECT count(*) FROM case_transitions WHERE case_id = ?", caseId))
                .isGreaterThanOrEqualTo(2);
        assertThat(countInTenant(tenantId, "SELECT count(*) FROM audit_events WHERE action = 'case.transition' "
                + "AND target_id = ? AND details->>'to' = 'Submitted' AND actor_user_id = ?", id, approver.userId()))
                .isEqualTo(1);
    }

    @Test
    void approverCannotApproveOwnPreparation() {
        JsonNode ready = readyCase(approver);

        ResponseEntity<String> response = transition(approver.token(), ready.path("id").asText(), "Submitted", null,
                ready.path("version").asLong());

        assertProblem(response, HttpStatus.CONFLICT, "CASE_SELF_APPROVAL");
        assertThat(ok(get("/api/cases/" + ready.path("id").asText(), approver.token())).path("status").asText())
                .isEqualTo("Ready");
    }

    @Test
    void sendBackRequiresReason() {
        JsonNode ready = readyCase(analyst);
        String id = ready.path("id").asText();
        long version = ready.path("version").asLong();

        ResponseEntity<String> withoutReason = transition(approver.token(), id, "InProgress", null, version);
        assertProblem(withoutReason, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION");
        assertThat(json(withoutReason).path("errors").get(0).path("field").asText()).isEqualTo("reason");
        assertProblem(transition(approver.token(), id, "InProgress", "  ", version),
                HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION");

        JsonNode sentBack = ok(transition(approver.token(), id, "InProgress", "3DS kaydi okunmuyor", version));
        assertThat(sentBack.path("status").asText()).isEqualTo("InProgress");
        assertThat(sentBack.path("preparedBy").isNull()).isTrue();
        JsonNode transitions = sentBack.path("transitions");
        assertThat(transitions.get(transitions.size() - 1).path("reason").asText()).isEqualTo("3DS kaydi okunmuyor");
    }

    @Test
    void readyRequiresEvidence() {
        JsonNode created = createCase(analyst.token());
        assertThat(created.path("missing")).hasSize(2);

        assertProblem(transition(analyst.token(), created.path("id").asText(), "Ready", null,
                created.path("version").asLong()), HttpStatus.CONFLICT, "EVIDENCE_INCOMPLETE");
    }

    @Test
    void addingEvidenceToReadyCaseResetsToInProgressAndClearsPreparer() {
        JsonNode ready = readyCase(analyst);

        JsonNode updated = ok(addEvidence(analyst.token(), ready.path("id").asText(), "CustomerCommunication"));

        assertThat(updated.path("status").asText()).isEqualTo("InProgress");
        assertThat(updated.path("preparedBy").isNull()).isTrue();
        assertThat(updated.path("evidence")).hasSize(3);
        assertThat(updated.path("version").asLong()).isGreaterThan(ready.path("version").asLong());
    }

    @Test
    void invalidTransitionReturns409() {
        JsonNode created = createCase(analyst.token());

        assertProblem(transition(approver.token(), created.path("id").asText(), "Won", null,
                created.path("version").asLong()), HttpStatus.CONFLICT, "INVALID_TRANSITION");
    }

    @Test
    void staleVersionReturns409() {
        JsonNode ready = readyCase(analyst);
        String id = ready.path("id").asText();
        long version = ready.path("version").asLong();

        ResponseEntity<String> first = transition(approver.token(), id, "Submitted", null, version);
        ResponseEntity<String> second = transition(approver.token(), id, "Submitted", null, version);

        assertThat(first.getStatusCode()).as(first.getBody()).isEqualTo(HttpStatus.OK);
        assertProblem(second, HttpStatus.CONFLICT, "CONFLICT");
        assertThat(countInTenant(tenantId, "SELECT count(*) FROM audit_events WHERE action = 'case.transition' "
                + "AND target_id = ? AND details->>'to' = 'Submitted'", id)).isEqualTo(1);
        assertThat(countInTenant(tenantId, "SELECT count(*) FROM case_transitions WHERE case_id = ? "
                + "AND to_status = 'Submitted'", UUID.fromString(id))).isEqualTo(1);
    }

    @Test
    void assigneeMustBeActiveAnalystOrAbove() {
        String id = createCase(analyst.token()).path("id").asText();
        Member viewer = member(slug, "VIEWER");
        Member removed = member(slug, "ANALYST", "REMOVED");
        String other = unique("globex");
        seedTenant(other, "ACTIVE");
        Member outsider = member(other, "ANALYST");

        for (UUID invalid : new UUID[] {viewer.userId(), outsider.userId(), removed.userId(), UUID.randomUUID()}) {
            ResponseEntity<String> response = assign(analyst.token(), id, invalid);
            assertProblem(response, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION");
            assertThat(json(response).path("errors").get(0).path("field").asText()).isEqualTo("userId");
        }

        JsonNode assigned = ok(assign(analyst.token(), id, approver.userId()));
        assertThat(assigned.path("assignee").path("id").asText()).isEqualTo(approver.userId().toString());
        assertThat(assigned.path("assignee").path("email").asText()).isEqualTo(approver.email());
        JsonNode listed = ok(get("/api/cases", analyst.token())).get(0);
        assertThat(listed.path("assignee").path("email").asText()).isEqualTo(approver.email());
        assertThat(countInTenant(tenantId, "SELECT count(*) FROM audit_events WHERE action = 'case.assigned' "
                + "AND target_id = ?", id)).isEqualTo(1);

        JsonNode unassigned = ok(assign(analyst.token(), id, null));
        assertThat(unassigned.path("assignee").isNull()).isTrue();
    }

    @Test
    void createWritesInitialTransitionAndAudit() {
        JsonNode created = createCase(analyst.token());
        String id = created.path("id").asText();

        assertThat(created.path("status").asText()).isEqualTo("Open");
        assertThat(created.path("version").asLong()).isZero();
        assertThat(created.path("assignee").isNull()).isTrue();
        assertThat(created.path("transitions").get(0).path("from").isNull()).isTrue();
        assertThat(created.path("transitions").get(0).path("to").asText()).isEqualTo("Open");
        assertThat(countInTenant(tenantId, "SELECT count(*) FROM audit_events WHERE action = 'case.created' "
                + "AND target_id = ?", id)).isEqualTo(1);
        assertThat(countInTenant(tenantId, "SELECT count(*) FROM dispute_cases WHERE id = ? AND created_by = ?",
                UUID.fromString(id), analyst.userId())).isEqualTo(1);
    }
}
