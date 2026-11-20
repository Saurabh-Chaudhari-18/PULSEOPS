-- PulseOps V8: Row-Level Security (ADR-006, defense in depth).
--
-- The backend ALWAYS filters by organization_id in application code; RLS is the
-- backstop so a missed filter fails CLOSED (returns nothing) instead of leaking
-- another tenant's data. The backend sets the tenant per transaction:
--     SET LOCAL app.current_org_id = '<uuid>';
-- pulseops_current_org_id() returns NULL when unset → tenant policies match no rows.
--
-- FORCE ROW LEVEL SECURITY makes policies apply even to the table owner.
-- Superusers and BYPASSRLS roles always bypass RLS — the application must never
-- connect as either (see docs/DATABASE.md).
--
-- Maintenance jobs (retention cleanup) iterate organizations and set
-- app.current_org_id per organization — there is deliberately NO bypass policy.

-- organizations: a tenant context sees only its own row. Creating an organization
-- happens BEFORE a tenant context exists, so INSERT is allowed with no context
-- (the insert runs only inside the audited organization-creation service).
ALTER TABLE organizations ENABLE ROW LEVEL SECURITY;
ALTER TABLE organizations FORCE ROW LEVEL SECURITY;
CREATE POLICY org_select ON organizations FOR SELECT
    USING (id = pulseops_current_org_id());
CREATE POLICY org_insert ON organizations FOR INSERT
    WITH CHECK (pulseops_current_org_id() IS NULL OR id = pulseops_current_org_id());
CREATE POLICY org_update ON organizations FOR UPDATE
    USING (id = pulseops_current_org_id());
CREATE POLICY org_delete ON organizations FOR DELETE
    USING (id = pulseops_current_org_id());

-- roles: system roles (organization_id IS NULL) readable by all; only custom
-- org roles may be written from a tenant context. (System roles are seeded by
-- migrations that run before this one, or by a BYPASSRLS migration role.)
ALTER TABLE roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE roles FORCE ROW LEVEL SECURITY;
CREATE POLICY roles_read ON roles FOR SELECT
    USING (organization_id IS NULL OR organization_id = pulseops_current_org_id());
CREATE POLICY roles_write ON roles
    USING (organization_id = pulseops_current_org_id())
    WITH CHECK (organization_id = pulseops_current_org_id());

-- audit_logs: tenant rows isolated per organization; platform-level rows
-- (organization_id IS NULL, e.g. user registration) are written and read only
-- OUTSIDE a tenant context.
ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_logs FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_tenant ON audit_logs
    USING (organization_id = pulseops_current_org_id())
    WITH CHECK (organization_id = pulseops_current_org_id());
CREATE POLICY audit_platform ON audit_logs
    USING (organization_id IS NULL AND pulseops_current_org_id() IS NULL)
    WITH CHECK (organization_id IS NULL AND pulseops_current_org_id() IS NULL);

-- All remaining tenant-owned tables share the same strict policy shape.
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'organization_members',
        'projects',
        'services',
        'integration_credentials',
        'integration_connections',
        'telemetry_events',
        'api_metrics',
        'application_logs',
        'distributed_traces',
        'database_operations',
        'anomalies',
        'incidents',
        'incident_anomalies',
        'incident_recommendations',
        'incident_comments',
        'incident_events',
        'alert_rules',
        'alert_notifications',
        'data_retention_policies'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I
                 USING (organization_id = pulseops_current_org_id())
                 WITH CHECK (organization_id = pulseops_current_org_id())', t);
    END LOOP;
END $$;

-- NOT under RLS (deliberate):
--   users, refresh_tokens, user_action_tokens — authentication happens before a
--   tenant context exists; these are user-scoped and enforced in the service layer.
--   permissions, role_permissions — global catalog, read-only reference data.
