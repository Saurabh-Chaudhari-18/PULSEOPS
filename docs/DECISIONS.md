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

## ADR-009 — Tenant context applied per pooled-connection checkout; user-scoped RLS (2026-11-21)

**Decision:** A `DataSource` wrapper (`TenantAwareDataSource`) runs `set_config('app.current_org_id', …, false), set_config('app.current_user_id', …, false)` on **every** connection checkout, writing empty strings when no context is bound. V9 adds permissive policies so a user context alone can read *its own* memberships and the organizations it actively belongs to.

**Why:** Session-level settings overwritten on every checkout cannot leak between pooled requests (verified with a single-connection pool test). `SET LOCAL` per transaction would need hooks in every transaction path, including JdbcTemplate outside JPA. The user-scoped policies are required because membership must be checked *before* an organization context exists. Rule that follows: the tenant context must be bound before a transaction opens (the interceptor does this; organization creation binds the new org's ID first).

## ADR-010 — Application-assigned UUIDs (2026-11-21)

**Decision:** Entities get `UUID.randomUUID()` in Java (`BaseEntity` implements `Persistable` for correct INSERT semantics); DB defaults remain for SQL-side inserts.

**Why:** Creating an organization under RLS requires knowing its ID before the INSERT so the tenant context can be bound to it.

## ADR-011 — Refresh token in an HttpOnly SameSite=Strict cookie (2026-11-21)

**Decision:** Access token in the JSON body (kept in memory by the SPA); refresh token only in an HttpOnly, Secure, SameSite=Strict cookie scoped to `/api/v1/auth`, plus a required custom header on cookie endpoints.

**Why:** XSS cannot read the long-lived credential; CSRF is blocked by SameSite plus the preflight-gated header; no CSRF token machinery is needed for bearer-authenticated API calls.

## ADR-012 — Membership and permissions resolved per request (2026-11-21)

**Decision:** JWTs carry only the user ID. Each organization-scoped request resolves membership + permissions with one indexed query.

**Why:** Removing a member or changing a role takes effect immediately instead of after token expiry; tokens stay small. Cost: one query per request (cacheable later if profiling shows need).

## ADR-013 — Offline verification method while registries are blocked (2026-11-21)

**Decision:** Until Maven can run, Java code is verified by (1) a javac-parser syntax check over all sources, (2) full javac attribution without dependencies, filtering out errors caused purely by absent external libraries, and (3) executing every embedded SQL statement against PostgreSQL 16 under the same tenant contexts the code sets. Unit tests are written but have **not been executed**.

**Why:** Gives real evidence about our own code (syntax, cross-class types, SQL, RLS behaviour) without claiming more than was verified. Spring/Hibernate API usage is only validated by the first real build.

## ADR-014 — Mergeable fixed-bucket latency histograms (2026-11-21)

**Decision:** Each `api_metrics` minute row stores a 25-bucket latency histogram (`duration_buckets bigint[]`, bounds 1 ms … 60 s + overflow) alongside count/sum/min/max. Concurrent writes and time-range queries merge histograms by element-wise addition (`pulseops_histogram_add` / `pulseops_histogram_sum`); p50/p95/p99 are computed from the merged histogram with linear interpolation.

**Why:** Percentiles cannot be averaged across minutes or across partial flushes; any dashboard showing "p95 over the last hour" from per-minute p95s would be wrong. Bucketed histograms (the Prometheus approach) are exactly mergeable, cheap, and their error is bounded by bucket width. The stored `p50_ms/p95_ms/p99_ms` columns stay unused (nullable) in v1.

## ADR-015 — Credential lookup via hash-scoped RLS policy (2026-11-21)

**Decision:** Ingestion authenticates a key by binding its SHA-256 hash as the transaction-local setting `app.credential_hash`; a SELECT-only policy reveals exactly the credential row with that hash. After the row identifies the organization, all further work runs under that organization's tenant context.

**Why:** Keeps FORCE RLS on `integration_credentials` with no bypass role or SECURITY DEFINER escape hatch. Seeing a row requires possessing its plaintext key; the setting cannot leak because it is transaction-local.

## ADR-016 — Bounded in-memory processing queue, at-most-once (2026-11-21)

**Decision:** Validated batches go into an `ArrayBlockingQueue` (default 200 batches) drained by a small worker pool (default 2). A full queue returns 503 with `Retry-After`. Graceful shutdown drains for up to 20 s.

**Why:** The spec forbids Kafka in v1 and targets 8 GB machines. A bounded queue gives back-pressure and predictable memory. Trade-off: batches queued during a crash are lost (documented in KNOWN_ISSUES); a durable outbox table or a broker can replace the queue behind `TelemetryProcessor.submit` later.

## ADR-017 — Validation before acknowledgement; services never auto-created (2026-11-21)

**Decision:** Authentication, scope resolution and full event validation happen synchronously; only valid batches get 202. Telemetry naming an unknown service is rejected (404 `UNKNOWN_SERVICE`) rather than creating it.

**Why:** Clients get precise, actionable errors at send time instead of silent drops later. Auto-creating services from telemetry would let a misconfigured or compromised sender pollute a tenant's service catalog and bypass the project's management permissions.
