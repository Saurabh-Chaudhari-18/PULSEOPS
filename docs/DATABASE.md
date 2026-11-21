# PulseOps — Database Design

PostgreSQL 15/16. Schema owned entirely by versioned Flyway migrations in
`backend/src/main/resources/db/migration` (ADR-003). `ddl-auto=validate` always;
never edit an applied migration — add a new one.

## Conventions

- UUID primary keys (`gen_random_uuid()`), proper FKs, CHECK constraints on every enum-like column.
- `created_at`/`updated_at timestamptz` with the `pulseops_set_updated_at()` trigger.
- Soft delete (`deleted_at`) on `projects`/`services`; partial unique indexes ignore soft-deleted rows.
- Every tenant-owned row carries `organization_id` (directly), FK → `organizations`.
- Pagination expected on all list queries; no unbounded scans in application code.

## Table Groups (27 tables)

| Group | Tables |
|---|---|
| Identity | `users`, `organizations`, `organization_members`, `roles`, `permissions`, `role_permissions`, `refresh_tokens`, `user_action_tokens` |
| Application mgmt | `projects`, `services`, `integration_credentials`, `integration_connections` |
| Telemetry | `telemetry_events`, `api_metrics` (1-min aggregates), `application_logs`, `distributed_traces` (spans, unique per tenant + trace + span ID), `database_operations`, `metric_points` (OTLP metrics, 1-min aggregates per series) |
| Intelligence | `anomalies`, `incidents`, `incident_anomalies`, `incident_recommendations`, `incident_comments`, `incident_events` |
| Alerting | `alert_rules`, `alert_notifications` |
| Administration | `audit_logs`, `data_retention_policies` |

Highlights:
- **`api_metrics`** is aggregates-first (ADR-007): unique per (service, route template, method, status class, bucket). Route templates are low-cardinality (`/orders/{id}`).
- **`integration_credentials`** stores SHA-256 hashes only; non-secret `key_prefix` for display; rotation chain via `rotated_from_id`; status/expiry/revocation/last-used tracked.
- **`incidents`** enforce one *active* incident per `(organization, dedup_key)` via a partial unique index — duplicate incidents for one ongoing event are impossible at the DB level.
- **`incident_events`** is the auditable incident timeline (every transition recorded).
- **`refresh_tokens` / `user_action_tokens`** store hashes only; rotation chain via `replaced_by_id`.
- High-volume tables carry BRIN time indexes; retention bounded via `data_retention_policies` + cleanup jobs (Phase 9+).

## Row-Level Security (ADR-006)

- `ENABLE` + `FORCE ROW LEVEL SECURITY` on 22 tenant tables (V8).
- Policies bind to `pulseops_current_org_id()` ← `SET LOCAL app.current_org_id = '<uuid>'`, set per transaction by the backend. Unset context ⇒ NULL ⇒ **fail closed** (no rows).
- Special cases: `organizations` allows INSERT before a tenant context exists (org creation); `roles` exposes system roles (org NULL) read-only; `audit_logs` has a platform-level policy for rows outside any org.
- Not under RLS by design: `users`, `refresh_tokens`, `user_action_tokens` (pre-tenant auth, service-layer enforced), `permissions`, `role_permissions` (global read-only catalog).
- **Operational rule:** the application must connect as `pulseops_app` — never a superuser or BYPASSRLS role, which bypass RLS entirely.
- Maintenance jobs iterate organizations and set the context per org; there is **no** RLS bypass policy.
- Seed migrations that write system rows must run **before** V8 or use a dedicated BYPASSRLS migration role (documented trade-off in V7).
- **V9 user-scoped policies** (ADR-009): with `app.current_user_id` bound, a user can read their own `organization_members` rows and the organizations where they hold an *active* membership — needed to check membership before an organization context exists. These are permissive SELECT-only policies; writes still require the organization context.
- **Backend wiring:** `TenantAwareDataSource` sets both settings on every pooled-connection checkout (empty when unbound), so stale tenants cannot leak between requests. The context must be bound before a transaction opens.

## V10 — ingestion support

- `credential_by_secret` policy (ADR-015): SELECT on `integration_credentials` only for the row whose `secret_hash` equals the transaction-local `app.credential_hash`.
- `api_metrics.duration_buckets bigint[]` + `pulseops_histogram_add(a, b)` and aggregate `pulseops_histogram_sum(bigint[])` (ADR-014). Merging upsert: `ON CONFLICT (...) DO UPDATE SET duration_buckets = pulseops_histogram_add(api_metrics.duration_buckets, EXCLUDED.duration_buckets)`.
- `integration_credentials_project_idx` for listing keys per project.


## V11 — OTLP support

- `distributed_traces`: the global `UNIQUE (trace_id, span_id)` becomes `UNIQUE (organization_id, trace_id, span_id)` (ADR-018); span writes use `ON CONFLICT DO NOTHING`, so exporter retries are idempotent and tenants cannot block each other's IDs.
- `metric_points`: one row per (service, metric name, canonical attribute key, minute bucket) with `sample_count`, `value_sum`, `value_min`, `value_max`, `value_last`; kind ∈ gauge/sum/histogram/summary. Writes merge with an upsert (counts and sums add, min/max widen, last is replaced when present). FORCE RLS `tenant_isolation`; included in `data_retention_policies`.

## Verification

`scripts/verify_migrations.sh` recreates a scratch DB, applies all migrations **as the non-superuser app role**, and asserts: seed catalog, cross-tenant write rejected, cross-tenant read empty, no-context fail-closed, user-scoped membership visibility, no joining foreign organizations via user context, removed members losing visibility. Run it after every schema change. Includes V10 checks (credential row visible only with its hash, setting cleared at commit, no writes via hash context, histogram addition) and V11 checks (per-tenant span uniqueness and retry idempotence, metric upsert arithmetic, `metric_points` isolation). Migrations are applied in numeric version order like Flyway. Last run: 2026-11-21, PostgreSQL 16.15, V1–V11 — PASS.

`TenantIsolationIntegrationTest` (backend) repeats the core checks through the real `TenantAwareDataSource` over a one-connection pool when `PULSEOPS_TEST_DB_URL` is set.

## Backup & Recovery

- **Backup:** `pg_dump -Fc pulseops > pulseops_$(date +%F).dump` (nightly recommended); include `--no-owner` for portability. For self-hosted Docker: `docker compose exec postgres pg_dump -U pulseops_app -Fc pulseops > backup.dump`.
- **Restore:** `pg_restore -d pulseops --clean --if-exists backup.dump`, then start the backend (Flyway validates schema version).
- Test restores regularly; a backup that has never been restored is not a backup.

## Future

Storage interfaces (`TelemetryStore`, Phase 6+) isolate relational storage so ClickHouse (or another analytical store) can take over raw telemetry later. Optional `pgvector` for incident similarity is a possible future migration; not in v1.
