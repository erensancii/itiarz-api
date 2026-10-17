-- Multi-tenancy schema (spec §4). Prototype demo data is discarded; the seed is regenerated.
DELETE FROM evidence_items;
DELETE FROM dispute_cases;

CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    keycloak_org_id VARCHAR(80) NOT NULL UNIQUE,
    slug VARCHAR(63) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    keycloak_user_id VARCHAR(80) NOT NULL UNIQUE,
    email VARCHAR(320) NOT NULL,
    display_name VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ
);

CREATE TABLE memberships (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    user_id UUID REFERENCES app_users(id),
    invited_email VARCHAR(320) NOT NULL CHECK (invited_email = lower(invited_email)),
    role VARCHAR(20) NOT NULL CHECK (role IN ('TENANT_ADMIN', 'APPROVER', 'ANALYST', 'VIEWER')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('INVITED', 'ACTIVE', 'REMOVED')),
    invited_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_memberships_tenant_email UNIQUE (tenant_id, invited_email),
    CONSTRAINT uq_memberships_tenant_user UNIQUE (tenant_id, user_id)
);

CREATE INDEX idx_memberships_user_id ON memberships(user_id);

ALTER TABLE dispute_cases
    ADD COLUMN tenant_id UUID NOT NULL REFERENCES tenants(id),
    ADD COLUMN assignee_id UUID REFERENCES app_users(id),
    ADD COLUMN created_by UUID NOT NULL REFERENCES app_users(id),
    ADD COLUMN prepared_by UUID REFERENCES app_users(id),
    ADD COLUMN approved_by UUID REFERENCES app_users(id),
    ADD COLUMN outcome_by UUID REFERENCES app_users(id),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    DROP CONSTRAINT uq_cases_provider_reference,
    ADD CONSTRAINT uq_cases_tenant_provider_reference UNIQUE (tenant_id, provider, provider_case_id),
    -- target for composite FKs so child rows can never point at another tenant's case
    ADD CONSTRAINT uq_cases_tenant_id UNIQUE (tenant_id, id);

DROP INDEX idx_cases_deadline_utc;
CREATE INDEX idx_cases_tenant_deadline ON dispute_cases(tenant_id, deadline_utc);
CREATE INDEX idx_cases_tenant_status ON dispute_cases(tenant_id, status);

ALTER TABLE evidence_items
    ADD COLUMN tenant_id UUID NOT NULL REFERENCES tenants(id),
    ADD COLUMN created_by UUID NOT NULL REFERENCES app_users(id),
    ADD CONSTRAINT fk_evidence_tenant_case FOREIGN KEY (tenant_id, case_id)
        REFERENCES dispute_cases(tenant_id, id) ON DELETE CASCADE;

CREATE TABLE case_transitions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    case_id UUID NOT NULL,
    from_status VARCHAR(25),
    to_status VARCHAR(25) NOT NULL,
    actor_id UUID REFERENCES app_users(id),
    reason VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- history is append-only: a case with transitions cannot be deleted silently
    CONSTRAINT fk_transitions_tenant_case FOREIGN KEY (tenant_id, case_id)
        REFERENCES dispute_cases(tenant_id, id)
);

CREATE INDEX idx_transitions_tenant_case ON case_transitions(tenant_id, case_id, occurred_at);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    tenant_id UUID REFERENCES tenants(id), -- NULL = platform event
    actor_user_id UUID REFERENCES app_users(id),
    action VARCHAR(80) NOT NULL,
    target_type VARCHAR(40),
    target_id VARCHAR(80),
    details JSONB,
    ip INET,
    trace_id VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_tenant_occurred ON audit_events(tenant_id, occurred_at DESC);
