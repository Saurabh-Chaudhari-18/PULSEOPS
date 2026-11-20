-- PulseOps V6: alerting, audit logging, data retention.

CREATE TABLE alert_rules (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id      uuid REFERENCES projects(id) ON DELETE CASCADE,   -- NULL = org-wide
    service_id      uuid REFERENCES services(id) ON DELETE CASCADE,   -- NULL = project/org-wide
    name            text NOT NULL CHECK (length(trim(name)) BETWEEN 2 AND 160),
    metric          text NOT NULL
                    CHECK (metric IN ('error_rate','p95_latency','p99_latency','avg_latency',
                                      'request_rate','availability','db_duration','service_unavailable')),
    operator        text NOT NULL CHECK (operator IN ('gt','gte','lt','lte')),
    threshold       double precision NOT NULL,
    window_seconds  int NOT NULL DEFAULT 300 CHECK (window_seconds BETWEEN 60 AND 86400),
    severity        text NOT NULL CHECK (severity IN ('critical','high','medium','low')),
    enabled         bool NOT NULL DEFAULT true,
    created_by      uuid REFERENCES users(id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX alert_rules_org_idx ON alert_rules (organization_id, enabled);
CREATE TRIGGER alert_rules_updated_at BEFORE UPDATE ON alert_rules
    FOR EACH ROW EXECUTE FUNCTION pulseops_set_updated_at();

CREATE TABLE alert_notifications (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    rule_id         uuid NOT NULL REFERENCES alert_rules(id) ON DELETE CASCADE,
    incident_id     uuid REFERENCES incidents(id) ON DELETE SET NULL,
    anomaly_id      uuid REFERENCES anomalies(id) ON DELETE SET NULL,
    channel         text NOT NULL CHECK (channel IN ('email','webhook','log')),
    destination     text NOT NULL,          -- address/URL; validated per channel in the backend
    status          text NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','sent','failed')),
    sent_at         timestamptz,
    error_message   text,
    payload         jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX alert_notifications_org_time_idx ON alert_notifications (organization_id, created_at DESC);
CREATE INDEX alert_notifications_rule_idx ON alert_notifications (rule_id);

-- Append-only audit trail. organization_id NULL = platform-level action (e.g. user registration).
CREATE TABLE audit_logs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid REFERENCES organizations(id) ON DELETE SET NULL,
    actor_id        uuid REFERENCES users(id) ON DELETE SET NULL,
    action          text NOT NULL CHECK (length(action) <= 120),
    resource_type   text NOT NULL CHECK (length(resource_type) <= 60),
    resource_id     uuid,
    ip_address      inet,
    user_agent      text,
    details         jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_logs_org_time_idx ON audit_logs (organization_id, created_at DESC);
CREATE INDEX audit_logs_actor_idx ON audit_logs (actor_id, created_at DESC);

CREATE TABLE data_retention_policies (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    data_type       text NOT NULL
                    CHECK (data_type IN ('telemetry_events','api_metrics','application_logs',
                                         'distributed_traces','database_operations','anomalies')),
    retention_days  int NOT NULL CHECK (retention_days BETWEEN 1 AND 3650),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, data_type)
);
CREATE TRIGGER data_retention_policies_updated_at BEFORE UPDATE ON data_retention_policies
    FOR EACH ROW EXECUTE FUNCTION pulseops_set_updated_at();
