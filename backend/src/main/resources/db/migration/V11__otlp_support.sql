-- PulseOps V11: OpenTelemetry (OTLP) ingestion support.

-- 1. Span uniqueness must be per tenant.
--
-- V4 declared UNIQUE (trace_id, span_id) across ALL organizations. Trace context crosses
-- company boundaries (a traceparent header sent from tenant A's service to tenant B's API
-- gives both sides the same trace_id), so a global key would let one tenant's spans
-- collide with — and, with ON CONFLICT DO NOTHING, silently suppress — another tenant's.
ALTER TABLE distributed_traces DROP CONSTRAINT distributed_traces_trace_id_span_id_key;
ALTER TABLE distributed_traces
    ADD CONSTRAINT distributed_traces_org_trace_span_uq UNIQUE (organization_id, trace_id, span_id);
DROP INDEX distributed_traces_trace_idx;
-- The unique constraint's index (organization_id, trace_id, span_id) also serves trace lookups.

-- 2. Runtime/custom metrics from OTLP (JVM memory, GC, connection pools, …), downsampled to
-- one row per (service, metric, attribute set, minute). Gauges/sums keep min/max/last/avg;
-- histograms keep count and sum. Rows merge exactly under concurrent writes.
CREATE TABLE metric_points (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    service_id      uuid NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    name            text NOT NULL CHECK (length(name) BETWEEN 1 AND 255),
    unit            text CHECK (length(unit) <= 64),
    kind            text NOT NULL CHECK (kind IN ('gauge','sum','histogram','summary')),
    attributes      jsonb NOT NULL DEFAULT '{}'::jsonb,
    attributes_key  text NOT NULL CHECK (length(attributes_key) <= 1000),  -- canonical form of attributes
    bucket_start    timestamptz NOT NULL,
    sample_count    bigint NOT NULL CHECK (sample_count >= 0),
    value_sum       double precision NOT NULL,
    value_min       double precision,
    value_max       double precision,
    value_last      double precision,
    UNIQUE (service_id, name, attributes_key, bucket_start)
);
CREATE INDEX metric_points_org_time_idx ON metric_points (organization_id, bucket_start DESC);
CREATE INDEX metric_points_service_name_time_idx ON metric_points (service_id, name, bucket_start DESC);
CREATE INDEX metric_points_time_brin ON metric_points USING brin (bucket_start);

ALTER TABLE metric_points ENABLE ROW LEVEL SECURITY;
ALTER TABLE metric_points FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON metric_points
    USING (organization_id = pulseops_current_org_id())
    WITH CHECK (organization_id = pulseops_current_org_id());

-- metric_points joins the retention-managed data types.
ALTER TABLE data_retention_policies DROP CONSTRAINT data_retention_policies_data_type_check;
ALTER TABLE data_retention_policies ADD CONSTRAINT data_retention_policies_data_type_check
    CHECK (data_type IN ('telemetry_events','api_metrics','application_logs','distributed_traces',
                         'database_operations','anomalies','metric_points'));
