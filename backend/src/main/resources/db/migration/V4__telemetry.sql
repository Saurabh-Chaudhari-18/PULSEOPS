-- PulseOps V4: telemetry storage (ADR-007: aggregates first; raw tables bounded by retention).
-- High-volume tables use BRIN time indexes (cheap for append-mostly data) and
-- carry organization_id for isolation even where service_id implies it.

-- Raw custom events from REST ingestion (bounded by retention policies).
CREATE TABLE telemetry_events (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    service_id      uuid NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    event_type      text NOT NULL
                    CHECK (event_type IN ('custom','error','performance','metric','business')),
    name            text NOT NULL CHECK (length(name) <= 200),
    occurred_at     timestamptz NOT NULL,
    received_at     timestamptz NOT NULL DEFAULT now(),
    attributes      jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX telemetry_events_service_time_idx ON telemetry_events (service_id, occurred_at DESC);
CREATE INDEX telemetry_events_time_brin ON telemetry_events USING brin (occurred_at);

-- Pre-aggregated API metrics: one row per (service, route template, method,
-- status class, 1-minute bucket). Route templates are low-cardinality
-- (e.g. /orders/{id}, never /orders/12345).
CREATE TABLE api_metrics (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    service_id      uuid NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    route_template  text NOT NULL CHECK (length(route_template) <= 300),
    http_method     text NOT NULL
                    CHECK (http_method IN ('GET','POST','PUT','PATCH','DELETE','HEAD','OPTIONS','OTHER')),
    status_class    text NOT NULL CHECK (status_class IN ('1xx','2xx','3xx','4xx','5xx')),
    bucket_start    timestamptz NOT NULL,
    bucket_seconds  int NOT NULL DEFAULT 60 CHECK (bucket_seconds > 0),
    request_count   bigint NOT NULL DEFAULT 0 CHECK (request_count >= 0),
    error_count     bigint NOT NULL DEFAULT 0 CHECK (error_count >= 0 AND error_count <= request_count),
    duration_sum_ms double precision NOT NULL DEFAULT 0,
    duration_min_ms double precision,
    duration_max_ms double precision,
    p50_ms          double precision,
    p95_ms          double precision,
    p99_ms          double precision,
    UNIQUE (service_id, route_template, http_method, status_class, bucket_start, bucket_seconds)
);
CREATE INDEX api_metrics_org_time_idx ON api_metrics (organization_id, bucket_start DESC);
CREATE INDEX api_metrics_service_time_idx ON api_metrics (service_id, bucket_start DESC);

CREATE TABLE application_logs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    service_id      uuid NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    occurred_at     timestamptz NOT NULL,
    level           text NOT NULL CHECK (level IN ('TRACE','DEBUG','INFO','WARN','ERROR','FATAL')),
    message         text NOT NULL,
    logger          text,
    trace_id        text CHECK (trace_id ~ '^[0-9a-f]{32}$'),
    span_id         text CHECK (span_id ~ '^[0-9a-f]{16}$'),
    attributes      jsonb NOT NULL DEFAULT '{}'::jsonb,
    received_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX application_logs_service_time_idx ON application_logs (service_id, occurred_at DESC);
CREATE INDEX application_logs_level_idx ON application_logs (service_id, level, occurred_at DESC);
CREATE INDEX application_logs_trace_idx ON application_logs (trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX application_logs_time_brin ON application_logs USING brin (occurred_at);

-- Spans of distributed traces.
CREATE TABLE distributed_traces (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    service_id      uuid NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    trace_id        text NOT NULL CHECK (trace_id ~ '^[0-9a-f]{32}$'),
    span_id         text NOT NULL CHECK (span_id ~ '^[0-9a-f]{16}$'),
    parent_span_id  text CHECK (parent_span_id ~ '^[0-9a-f]{16}$'),
    name            text NOT NULL CHECK (length(name) <= 300),
    kind            text NOT NULL DEFAULT 'INTERNAL'
                    CHECK (kind IN ('INTERNAL','SERVER','CLIENT','PRODUCER','CONSUMER')),
    start_time      timestamptz NOT NULL,
    duration_ms     double precision NOT NULL CHECK (duration_ms >= 0),
    status_code     text NOT NULL DEFAULT 'UNSET' CHECK (status_code IN ('UNSET','OK','ERROR')),
    status_message  text,
    attributes      jsonb NOT NULL DEFAULT '{}'::jsonb,
    received_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (trace_id, span_id)
);
CREATE INDEX distributed_traces_trace_idx ON distributed_traces (trace_id);
CREATE INDEX distributed_traces_service_time_idx ON distributed_traces (service_id, start_time DESC);
CREATE INDEX distributed_traces_time_brin ON distributed_traces USING brin (start_time);

-- Database operations observed in customer applications.
-- SQL parameters / sensitive query contents are never collected by default;
-- 'operation' holds a normalized statement shape only.
CREATE TABLE database_operations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    service_id      uuid NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    db_system       text NOT NULL CHECK (length(db_system) <= 60),
    operation_type  text NOT NULL
                    CHECK (operation_type IN ('SELECT','INSERT','UPDATE','DELETE','DDL','CALL','OTHER')),
    operation       text CHECK (length(operation) <= 500),
    table_name      text CHECK (length(table_name) <= 200),
    duration_ms     double precision NOT NULL CHECK (duration_ms >= 0),
    occurred_at     timestamptz NOT NULL,
    is_error        bool NOT NULL DEFAULT false,
    error_message   text,
    trace_id        text CHECK (trace_id ~ '^[0-9a-f]{32}$'),
    span_id         text CHECK (span_id ~ '^[0-9a-f]{16}$'),
    attributes      jsonb NOT NULL DEFAULT '{}'::jsonb,
    received_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX database_operations_service_time_idx ON database_operations (service_id, occurred_at DESC);
CREATE INDEX database_operations_slow_idx ON database_operations (service_id, duration_ms DESC);
CREATE INDEX database_operations_time_brin ON database_operations USING brin (occurred_at);
