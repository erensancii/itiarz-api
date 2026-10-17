-- Row-level security (spec §5). The app role (placeholder appRole, default itiarz_app) is
-- NOSUPERUSER NOBYPASSRLS and owns nothing; it only receives the explicit grants below.
-- Per-transaction settings: app.tenant_id, app.user_id, app.platform ('on') via set_config(..., true).

-- tenants: readable by all (resolution by slug), writable only on the platform path.
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenants_read ON tenants FOR SELECT USING (true);
CREATE POLICY tenants_platform_insert ON tenants FOR INSERT
    WITH CHECK (current_setting('app.platform', true) = 'on');
CREATE POLICY tenants_platform_update ON tenants FOR UPDATE
    USING (current_setting('app.platform', true) = 'on')
    WITH CHECK (current_setting('app.platform', true) = 'on');

-- Tenant-scoped tables.
ALTER TABLE dispute_cases ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispute_cases FORCE ROW LEVEL SECURITY;
CREATE POLICY dispute_cases_tenant ON dispute_cases FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE evidence_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence_items FORCE ROW LEVEL SECURITY;
CREATE POLICY evidence_items_tenant ON evidence_items FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE case_transitions ENABLE ROW LEVEL SECURITY;
ALTER TABLE case_transitions FORCE ROW LEVEL SECURITY;
CREATE POLICY case_transitions_tenant ON case_transitions FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE memberships FORCE ROW LEVEL SECURITY;
CREATE POLICY memberships_tenant ON memberships FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- A user may read their own memberships across tenants (GET /api/me, TenantResolutionFilter).
CREATE POLICY memberships_self_read ON memberships FOR SELECT
    USING (user_id = nullif(current_setting('app.user_id', true), '')::uuid);
-- Platform path: invite the first tenant admin, etc.
CREATE POLICY memberships_platform ON memberships FOR ALL
    USING (current_setting('app.platform', true) = 'on')
    WITH CHECK (current_setting('app.platform', true) = 'on');

ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_events FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_events_tenant ON audit_events FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- Platform admins read only platform events, never tenant audit.
CREATE POLICY audit_events_platform_read ON audit_events FOR SELECT
    USING (current_setting('app.platform', true) = 'on' AND tenant_id IS NULL);
-- Platform flows may write audit rows for any tenant (e.g. tenant creation audits into the new tenant).
CREATE POLICY audit_events_platform_insert ON audit_events FOR INSERT
    WITH CHECK (current_setting('app.platform', true) = 'on');

-- Grants for the application role.
GRANT SELECT, INSERT, UPDATE ON tenants TO "${appRole}";
GRANT SELECT, INSERT, UPDATE ON app_users TO "${appRole}";
GRANT SELECT, INSERT, UPDATE, DELETE ON memberships TO "${appRole}";
GRANT SELECT, INSERT, UPDATE, DELETE ON dispute_cases TO "${appRole}";
GRANT SELECT, INSERT, UPDATE, DELETE ON evidence_items TO "${appRole}";
GRANT SELECT, INSERT ON case_transitions TO "${appRole}";
GRANT SELECT, INSERT ON audit_events TO "${appRole}";
