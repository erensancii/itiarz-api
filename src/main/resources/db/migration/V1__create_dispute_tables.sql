CREATE TABLE dispute_cases (
    id UUID PRIMARY KEY,
    provider VARCHAR(80) NOT NULL,
    provider_case_id VARCHAR(80) NOT NULL,
    order_id VARCHAR(80) NOT NULL,
    reason VARCHAR(25) NOT NULL,
    amount NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    deadline_utc TIMESTAMPTZ NOT NULL,
    status VARCHAR(25) NOT NULL,
    created_utc TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_cases_provider_reference UNIQUE (provider, provider_case_id)
);

CREATE INDEX idx_cases_deadline_utc ON dispute_cases(deadline_utc);

CREATE TABLE evidence_items (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES dispute_cases(id) ON DELETE CASCADE,
    kind VARCHAR(40) NOT NULL,
    note VARCHAR(500) NOT NULL,
    created_utc TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_evidence_case_id ON evidence_items(case_id);
