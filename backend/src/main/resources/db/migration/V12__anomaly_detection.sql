-- PulseOps V12: anomaly detection support (ADR-023, ADR-024).

-- 1. Service discovery for background jobs.
--
-- The detection job must find services that are receiving telemetry across all tenants,
-- but integration_connections is under FORCE RLS. This SELECT-only policy opens the table
-- (and nothing else) while the transaction-local setting app.discovery = 'on' is set by
-- the job itself; the job then works on each organization under its normal tenant context.
-- Unset or any other value matches nothing; writes still require the tenant context.
CREATE POLICY connection_discovery ON integration_connections FOR SELECT
    USING (current_setting('app.discovery', true) = 'on');

-- 2. One active anomaly per (service, metric, scope); repeats update it instead of piling up.
ALTER TABLE anomalies
    ADD COLUMN scope        text NOT NULL DEFAULT 'service' CHECK (length(scope) BETWEEN 1 AND 300),
    ADD COLUMN reason       text,
    ADD COLUMN occurrences  integer NOT NULL DEFAULT 1 CHECK (occurrences >= 1),
    ADD COLUMN last_seen_at timestamptz,
    ADD COLUMN resolved_at  timestamptz;

UPDATE anomalies SET last_seen_at = detected_at WHERE last_seen_at IS NULL;
ALTER TABLE anomalies
    ALTER COLUMN last_seen_at SET NOT NULL,
    ALTER COLUMN last_seen_at SET DEFAULT now();

-- 'resolved': the condition has not been seen for a while (set by the job).
ALTER TABLE anomalies DROP CONSTRAINT anomalies_status_check;
ALTER TABLE anomalies ADD CONSTRAINT anomalies_status_check
    CHECK (status IN ('open', 'linked', 'dismissed', 'resolved'));

CREATE UNIQUE INDEX anomalies_active_uq ON anomalies (service_id, metric, scope)
    WHERE status IN ('open', 'linked');
CREATE INDEX anomalies_org_last_seen_idx ON anomalies (organization_id, last_seen_at DESC);
