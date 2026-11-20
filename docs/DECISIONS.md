# PulseOps — Architecture Decision Records

Short, dated records of decisions that shape the system. Newest last.

---

## ADR-001 — Modular monolith + separate ML service (2026-11-20)

**Decision:** One Spring Boot application organized as strictly separated packages per module (`com.pulseops.<module>`), plus one Python FastAPI ML service. No other services in v1.

**Why:** 8 GB RAM development machine; microservices add operational cost without benefit at this scale. Module boundaries (api / service / repository / domain per module, no cross-module repository access) keep future extraction possible.

## ADR-002 — Single Maven module, not multi-module (2026-11-20)

**Decision:** `backend/` is a single Maven module; module boundaries are enforced by package convention (and ArchUnit tests in Phase 17), not by Maven modules.

**Why:** Multi-module Maven multiplies build time and memory on constrained hardware. Package-level boundaries deliver the same discipline for a monolith this size.

## ADR-003 — Flyway with plain SQL migrations (2026-11-20)

**Decision:** Versioned plain-SQL Flyway migrations in `backend/src/main/resources/db/migration` (canonical copy; `database/migrations` symlink-free mirror is NOT kept — single source of truth in backend resources, referenced from docs). `ddl-auto=validate` everywhere; never `create`/`update` in production.

**Why:** Plain SQL is reviewable, uses PostgreSQL features directly (RLS, partial indexes, JSONB), and keeps one source of truth.

## ADR-004 — BCrypt for password hashing (2026-11-20)

**Decision:** BCrypt (strength 12) via spring-security-crypto, not Argon2.

**Why:** Spec allows either. Argon2 in Spring requires the BouncyCastle dependency; BCrypt is built in → one fewer dependency, well-audited. Revisit if threat model changes.

## ADR-005 — JWT access tokens + rotating opaque refresh tokens (2026-11-20)

**Decision:** Short-lived (15 min) HMAC-signed JWT access tokens validated by Spring's OAuth2 resource server (Nimbus, already on the classpath). Refresh tokens are opaque 256-bit random values, stored **hashed** (SHA-256) in `refresh_tokens`, rotated on every use, with reuse detection → revoke the whole chain.

**Why:** Resource-server validation avoids a custom filter chain and an extra JWT library. Opaque rotating refresh tokens allow server-side revocation; storing only hashes means a DB leak does not leak usable tokens.

## ADR-006 — Tenant isolation: application checks + PostgreSQL RLS (defense in depth) (2026-11-20)

**Decision:** Every tenant-owned table carries `organization_id`. The backend always filters by the authenticated organization. Additionally, RLS (`ENABLE` + `FORCE ROW LEVEL SECURITY`) is applied with policies bound to `current_setting('app.current_org_id')`, set per transaction by the backend. The application connects as a dedicated `pulseops_app` role that is **not** the table owner.

**Why:** A missed `WHERE organization_id = ?` must fail closed, not leak another tenant's data. FORCE covers the table-owner edge case.

## ADR-007 — Telemetry storage: aggregates in PostgreSQL, raw data bounded (2026-11-20)

**Decision:** API metrics are stored pre-aggregated per (service, route template, method, status class, 1-minute bucket). Raw events/logs/spans go to bounded tables with BRIN time indexes and retention cleanup jobs. A `TelemetryStore` interface isolates storage so ClickHouse can be introduced later without touching processing logic.

**Why:** Unlimited raw telemetry in relational tables does not scale and the spec forbids it; aggregation answers the dashboard queries cheaply.

## ADR-008 — Pinned framework versions pending first online build (2026-11-20)

**Decision:** Spring Boot pinned at 3.5.6 / Java 21; frontend pinned at React 19 / Vite 7 / MUI 7 / Recharts 3 / React Router 7 / TanStack Query 5. To be re-verified (and bumped to latest stable) at the first build once registry access is enabled.

**Why:** Registries are currently unreachable from the build workspace; versions chosen from last-known stable lines.
