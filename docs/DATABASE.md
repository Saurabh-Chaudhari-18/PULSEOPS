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
| Telemetry | `telemetry_events`, `api_metrics` (1-min aggregates), `application_logs`, `distributed_traces` (spans), `database_operations` |
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

## Verification

`scripts/verify_migrations.sh` recreates a scratch DB, applies all migrations **as the non-superuser app role**, and asserts: schema inventory, seed catalog, cross-tenant write rejected, cross-tenant read empty, no-context fail-closed, `updated_at` trigger. Run it after every schema change. Last run: 2026-11-20, PostgreSQL 16.15 — PASS.

## Backup & Recovery

- **Backup:** `pg_dump -Fc pulseops > pulseops_$(date +%F).dump` (nightly recommended); include `--no-owner` for portability. For self-hosted Docker: `docker compose exec postgres pg_dump -U pulseops_app -Fc pulseops > backup.dump`.
- **Restore:** `pg_restore -d pulseops --clean --if-exists backup.dump`, then start the backend (Flyway validates schema version).
- Test restores regularly; a backup that has never been restored is not a backup.

## Future

Storage interfaces (`TelemetryStore`, Phase 6+) isolate relational storage so ClickHouse (or another analytical store) can take over raw telemetry later. Optional `pgvector` for incident similarity is a possible future migration; not in v1.
