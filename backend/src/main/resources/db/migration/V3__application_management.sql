-- PulseOps V3: application management — projects, services, integration credentials & connections.

CREATE TABLE projects (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name             text NOT NULL CHECK (length(trim(name)) BETWEEN 2 AND 120),
    description      text,
    tech_stack       text,
    environment      text NOT NULL DEFAULT 'production'
                     CHECK (environment IN ('development','testing','staging','production','other')),
    region           text,
    application_type text,
    status           text NOT NULL DEFAULT 'active'
                     CHECK (status IN ('active','archived')),
    deleted_at       timestamptz,           -- soft delete
    created_by       uuid REFERENCES users(id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX projects_org_name_uq ON projects (organization_id, lower(name))
    WHERE deleted_at IS NULL;
CREATE INDEX projects_org_idx ON projects (organization_id);
CREATE TRIGGER projects_updated_at BEFORE UPDATE ON projects
    FOR EACH ROW EXECUTE FUNCTION pulseops_set_updated_at();

CREATE TABLE services (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id      uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name            text NOT NULL CHECK (length(trim(name)) BETWEEN 2 AND 120),
    description     text,
    technology      text NOT NULL DEFAULT 'other'
                    CHECK (technology IN ('java_spring_boot','python','nodejs','dotnet','go','custom_rest','opentelemetry','other')),
    status          text NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','archived')),
    deleted_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX services_project_name_uq ON services (project_id, lower(name))
    WHERE deleted_at IS NULL;
CREATE INDEX services_org_idx ON services (organization_id);
CREATE TRIGGER services_updated_at BEFORE UPDATE ON services
    FOR EACH ROW EXECUTE FUNCTION pulseops_set_updated_at();

-- Integration credentials: cryptographically random secrets, stored as hashes only,
-- displayed once at creation; support rotation, revocation, expiry, last-used.
CREATE TABLE integration_credentials (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id      uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    service_id      uuid REFERENCES services(id) ON DELETE CASCADE,  -- NULL = project-wide
    name            text NOT NULL CHECK (length(trim(name)) BETWEEN 2 AND 120),
    key_prefix      text NOT NULL,                 -- non-secret display prefix e.g. 'po_live_3f9a'
    secret_hash     text NOT NULL UNIQUE,          -- SHA-256 of the full secret
    status          text NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','revoked','expired')),
    expires_at      timestamptz,
    revoked_at      timestamptz,
    last_used_at    timestamptz,
    rotated_from_id uuid REFERENCES integration_credentials(id),
    created_by      uuid REFERENCES users(id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT credentials_revoked_consistent
        CHECK (status <> 'revoked' OR revoked_at IS NOT NULL)
);
CREATE INDEX integration_credentials_org_idx     ON integration_credentials (organization_id);
CREATE INDEX integration_credentials_service_idx ON integration_credentials (service_id);
CREATE TRIGGER integration_credentials_updated_at BEFORE UPDATE ON integration_credentials
    FOR EACH ROW EXECUTE FUNCTION pulseops_set_updated_at();

-- Connection state per service. 'connected' is set only after telemetry is actually
-- received — never merely because a credential was generated.
CREATE TABLE integration_connections (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    service_id         uuid NOT NULL UNIQUE REFERENCES services(id) ON DELETE CASCADE,
    integration_type   text NOT NULL
                       CHECK (integration_type IN ('java_agent','rest_api','otlp')),
    status             text NOT NULL DEFAULT 'not_connected'
                       CHECK (status IN ('not_connected','waiting_for_data','connected','connection_error','disabled')),
    first_telemetry_at timestamptz,
    last_telemetry_at  timestamptz,
    last_error         text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX integration_connections_org_idx ON integration_connections (organization_id);
CREATE TRIGGER integration_connections_updated_at BEFORE UPDATE ON integration_connections
    FOR EACH ROW EXECUTE FUNCTION pulseops_set_updated_at();
