-- PulseOps V10: support for credential authentication and mergeable latency histograms.

-- 1. Credential lookup without a tenant context.
--
-- Ingestion requests arrive with only an API key; the organization is unknown until the
-- credential is found, but integration_credentials is under FORCE RLS. This policy reveals
-- exactly ONE row: the one whose secret_hash equals the hash the caller presents in
--     set_config('app.credential_hash', '<sha256 hex>', true)   -- transaction-local
-- Possessing the plaintext key is therefore the only way to see its row; an empty or
-- unset setting matches nothing. SELECT only — writes still require the tenant context.
CREATE POLICY credential_by_secret ON integration_credentials FOR SELECT
    USING (secret_hash = NULLIF(current_setting('app.credential_hash', true), ''));

-- 2. Mergeable latency histograms (ADR-014).
--
-- Percentiles cannot be averaged or summed across minute buckets, so each api_metrics row
-- also stores request counts per fixed latency bucket. Rows (and concurrent partial
-- flushes of the same minute) merge by element-wise addition; p50/p95/p99 for any time
-- range are computed from the summed histogram. Bucket upper bounds (ms) are fixed in
-- code (LatencyHistogram.BOUNDS_MS); the final element counts everything above the last bound.
ALTER TABLE api_metrics ADD COLUMN duration_buckets bigint[] NOT NULL DEFAULT '{}';

-- Element-wise sum of two histograms (arrays of possibly different length).
CREATE FUNCTION pulseops_histogram_add(a bigint[], b bigint[]) RETURNS bigint[]
LANGUAGE sql IMMUTABLE AS $$
    SELECT coalesce(array_agg(coalesce(x, 0) + coalesce(y, 0) ORDER BY ord), '{}')
    FROM unnest(a, b) WITH ORDINALITY AS t(x, y, ord)
$$;

-- Aggregate form: SUM over histogram rows, e.g. SELECT pulseops_histogram_sum(duration_buckets) ...
CREATE AGGREGATE pulseops_histogram_sum(bigint[]) (
    SFUNC = pulseops_histogram_add,
    STYPE = bigint[],
    INITCOND = '{}'
);

-- 3. Fast lookup of a service's connection row during ingestion (service_id is already
-- UNIQUE, which provides the index) and of credentials per project for listing.
CREATE INDEX integration_credentials_project_idx ON integration_credentials (project_id, created_at DESC);
