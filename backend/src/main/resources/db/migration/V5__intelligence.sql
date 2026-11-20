-- PulseOps V5: intelligence — anomalies, incidents, correlation, recommendations.

CREATE TABLE anomalies (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id      uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    service_id      uuid NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    metric          text NOT NULL
                    CHECK (metric IN ('request_rate','response_latency','error_rate','db_duration','connection_utilization')),
    anomaly_type    text NOT NULL
                    CHECK (anomaly_type IN ('spike','drop','trend_shift','outlier','baseline_deviation')),
    severity        text NOT NULL CHECK (severity IN ('critical','high','medium','low')),
    observed_value  double precision NOT NULL,
    expected_value  double precision,
    score           double precision NOT NULL CHECK (score >= 0),
    detected_at     timestamptz NOT NULL DEFAULT now(),
    window_start    timestamptz NOT NULL,
    window_end      timestamptz NOT NULL,
    evidence        jsonb NOT NULL DEFAULT '{}'::jsonb,   -- series excerpts, thresholds, detector name
    status          text NOT NULL DEFAULT 'open'
                    CHECK (status IN ('open','linked','dismissed')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT anomalies_window_sane CHECK (window_end >= window_start)
);
CREATE INDEX anomalies_org_time_idx ON anomalies (organization_id, detected_at DESC);
CREATE INDEX anomalies_service_status_idx ON anomalies (service_id, status);
CREATE TRIGGER anomalies_updated_at BEFORE UPDATE ON anomalies
    FOR EACH ROW EXECUTE FUNCTION pulseops_set_updated_at();

CREATE TABLE incidents (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id       uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    service_id       uuid REFERENCES services(id) ON DELETE SET NULL,  -- NULL = multi-service
    title            text NOT NULL CHECK (length(trim(title)) BETWEEN 3 AND 300),
    summary          text,
    severity         text NOT NULL CHECK (severity IN ('critical','high','medium','low')),
    status           text NOT NULL DEFAULT 'open'
                     CHECK (status IN ('open','investigating','acknowledged','resolved','closed')),
    source           text NOT NULL DEFAULT 'automatic' CHECK (source IN ('automatic','manual')),
    dedup_key        text,                       -- prevents duplicate incidents for one ongoing event
    detected_at      timestamptz NOT NULL DEFAULT now(),
    acknowledged_at  timestamptz,
    resolved_at      timestamptz,
    closed_at        timestamptz,
    assigned_to      uuid REFERENCES users(id),
    created_by       uuid REFERENCES users(id),  -- set for manual incidents
    resolution_notes text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
-- Only one ACTIVE incident per dedup key per organization.
CREATE UNIQUE INDEX incidents_active_dedup_uq ON incidents (organization_id, dedup_key)
    WHERE dedup_key IS NOT NULL AND status NOT IN ('resolved','closed');
CREATE INDEX incidents_org_status_idx ON incidents (organization_id, status, detected_at DESC);
CREATE INDEX incidents_service_idx ON incidents (service_id);
CREATE TRIGGER incidents_updated_at BEFORE UPDATE ON incidents
    FOR EACH ROW EXECUTE FUNCTION pulseops_set_updated_at();

CREATE TABLE incident_anomalies (
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,  -- for RLS
    incident_id     uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    anomaly_id      uuid NOT NULL REFERENCES anomalies(id) ON DELETE CASCADE,
    linked_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (incident_id, anomaly_id)
);

CREATE TABLE incident_recommendations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id     uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    kind            text NOT NULL DEFAULT 'troubleshooting'
                    CHECK (kind IN ('root_cause_hypothesis','troubleshooting','historical_match')),
    title           text NOT NULL,
    description     text NOT NULL,
    confidence      double precision NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    rank            int NOT NULL DEFAULT 0,
    evidence        jsonb NOT NULL DEFAULT '{}'::jsonb,   -- supporting observations, log/trace refs
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX incident_recommendations_incident_idx ON incident_recommendations (incident_id, rank);

CREATE TABLE incident_comments (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id     uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    author_id       uuid NOT NULL REFERENCES users(id),
    body            text NOT NULL CHECK (length(trim(body)) > 0),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz
);
CREATE INDEX incident_comments_incident_idx ON incident_comments (incident_id, created_at);
CREATE TRIGGER incident_comments_updated_at BEFORE UPDATE ON incident_comments
    FOR EACH ROW EXECUTE FUNCTION pulseops_set_updated_at();

-- Auditable incident timeline: every transition and significant action.
CREATE TABLE incident_events (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id     uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    event_type      text NOT NULL
                    CHECK (event_type IN ('created','status_changed','severity_changed','assigned',
                                          'comment_added','anomaly_linked','recommendation_added','resolved_notes_set')),
    actor_id        uuid REFERENCES users(id),   -- NULL = system
    old_value       text,
    new_value       text,
    details         jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX incident_events_incident_idx ON incident_events (incident_id, created_at);
