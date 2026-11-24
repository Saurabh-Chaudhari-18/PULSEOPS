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

## ADR-018 — Span uniqueness is per tenant (2026-11-21)

**Decision:** V11 replaces `UNIQUE (trace_id, span_id)` on `distributed_traces` with `UNIQUE (organization_id, trace_id, span_id)`, and span inserts use `ON CONFLICT … DO NOTHING`.

**Why:** Trace and span IDs are chosen by the sender. With a global constraint, one tenant could pre-register another tenant's IDs (or two tenants could collide by accident), causing the victim's spans to be rejected — a cross-tenant denial of service and a timing side-channel on RLS-hidden rows. Scoped to the tenant, exporter retries are still idempotent for spans. Other derived rows (request metrics, DB operations, exception logs) are not deduplicated on retry; that is accepted because OTLP exporters only retry on 429/503/network errors, where the original request was not queued.

## ADR-019 — OTLP/HTTP protobuf receiver design (2026-11-21)

**Decision:** Implement only OTLP/HTTP with binary protobuf (the OpenTelemetry Java agent's default and supported by every SDK and the Collector), using the published `io.opentelemetry.proto:opentelemetry-proto` bindings. Protobuf reading is confined to `OtlpDecoder`; all interpretation (attribute limits and redaction, SQL sanitizing, span → request/DB/exception derivation, severity mapping, metric downsampling) lives in a protobuf-free `otlp.mapping` package. Decoded items become per-service `IngestBatch`es on the same bounded queue and `TelemetryStore` as REST. Per-item problems are reported through OTLP `partial_success`; errors use a hand-encoded `google.rpc.Status` (two fields; avoids the common-protos dependency).

**Why:** gRPC would add Netty/grpc-java and a second port for little gain at this scale; OTLP/JSON is rarely used by agents. Keeping interpretation protobuf-free made it verifiable without a Maven build (ADR-013) and keeps protocol and semantics separable. Metrics are downsampled to one-minute aggregates at ingestion because raw OTLP metric streams (every instrument × attribute set × export interval) would dominate storage on an 8 GB/self-hosted target; the cost is that histogram bucket shapes are not kept (count/sum/min/max only), documented in KNOWN_ISSUES.

## ADR-020 — Monitoring read model and default health rules (2026-11-23)

**Decision:** Monitoring APIs aggregate `api_metrics` on the fly per request (SQL `GROUP BY` + `pulseops_histogram_sum`), computing percentiles in Java from the merged histogram. Windows are aligned to steps of 1 min–1 day, capped at 31 days and ≤ 1440 points. Service health uses fixed defaults taken from the specification's alert examples — CRITICAL at ≥ 5 % server errors or p95 ≥ 1000 ms, WARNING at ≥ 1 % or ≥ 500 ms — and is not judged below 20 requests. Only 5xx count as errors; 4xx are shown separately.

**Why:** At the target scale (one-minute rows, a few dozen routes per service) on-the-fly aggregation over the `(service_id, bucket_start)` index is fast enough and always consistent with ingestion, so no rollup tables or caches are needed yet (revisit if 30-day queries exceed ~1 s). The health label is a transparent, documented rule with reasons attached, so users see *why* a service is flagged; learned baselines (Phase 12) and user-defined alert rules (Phase 15) will refine it rather than replace this explanation. Treating 4xx as client behaviour avoids flagging services for users' 404s.

## ADR-021 — Database monitoring over raw calls (2026-11-23)

**Decision:** Database monitoring queries `database_operations` directly (one row per call) and computes exact percentiles with `percentile_cont`, rather than pre-aggregating into histograms like `api_metrics`. Query shapes are grouped by (system, operation type, sanitized statement, table). Database health uses the HTTP error thresholds but tighter latency ones (p95 warning 200 ms, critical 1000 ms). Connection-pool gauges are read from OTLP metric points under both the stable (`db.client.connection.*`, `db.client.connection.state`) and older experimental (`db.client.connections.*`, `state`) names, because the Java agent emits the older names unless the stable database conventions are opted in.

**Why:** Raw rows are already stored for drill-down (each call keeps its trace ID, so the slowest call of a query links to its trace), and at the target scale exact percentiles over a time-bounded index range are affordable and more trustworthy than bucket estimates. A per-query p95 of 200 ms is already a strong signal because one request may run several queries. If retention or volume grows (KNOWN_ISSUES #27), add a per-minute rollup like `api_metrics` without changing the API.

## ADR-022 — Logs and traces explorer: keyset pagination, trace assembly at read time (2026-11-23)

**Decision:** Log and trace lists are ordered newest first and paginated with an opaque keyset cursor (last row's time and ID), not offsets. Traces are assembled at read time from `distributed_traces`: a trace matches when any of its spans falls in the window (optionally for one service) and is summarised over **all** its spans in the organization; the root is the span without a parent (else the earliest span, flagged `complete=false`). A trace view orders spans depth-first by start time; spans whose parent never arrived are shown as orphan roots rather than dropped. Message search is a case-insensitive substring match.

**Why:** Telemetry is append-heavy, so offset pages shift while a user reads them; keyset cursors are stable and use the time indexes. Storing only spans (no separate trace table) keeps ingestion simple and idempotent (ADR-018) and avoids reconciling partial traces whose spans arrive from several services at different times. Substring search needs no extra infrastructure; a trigram or full-text index can be added later if log volume demands it (KNOWN_ISSUES #29).

## ADR-023 — Anomaly detection by robust statistics with practical-significance guards (2026-11-23)

**Decision:** The ML service judges each per-minute series by comparing the median of the newest minutes (default 5) with a robust baseline of the preceding history (default 175 minutes): centre = median, spread = 1.4826 × MAD floored by per-metric absolute and relative minimums, score = robust z. A finding additionally needs a change that matters (e.g. p95 ≥ 50 ms and ≥ 1.5× above usual; error rate ≥ 2 points with ≥ 5 failed requests; request-rate drop to ≤ 50 % from ≥ 0.2 req/s; connection-pool use ≥ 50 % — added 2026-11-24 after a live 0 % → 20 % finding), at least 30 usable baseline minutes, and minutes backed by enough requests. Severity follows the size of the change. Every finding carries a sentence explaining it. Dependencies: numpy, FastAPI, pydantic only.

**Why:** Operators must trust and understand alerts, and the product must run on an 8 GB machine at zero cost. Median/MAD resists the outliers that poison mean/standard-deviation baselines; the evaluation-window median ignores single bad minutes; the guards stop near-constant small services from alarming on tiny wobbles. Model-based methods (isolation forests, forecasting with seasonality) can be added behind the same API when there is labelled history to validate them against; until then a transparent rule set is the honest choice. Limits: no daily/weekly seasonality, and a change that persists for more than ~half the history becomes the new baseline (KNOWN_ISSUES #31).

## ADR-024 — Stateless ML service fed by a backend job; anomalies stored by the backend (2026-11-23)

**Decision:** The backend owns data and scheduling: every minute a job finds services with recent telemetry (a SELECT-only RLS policy on `integration_connections` opened by the transaction-local `app.discovery` setting, as with V10's credential lookup), builds each service's series under that tenant's context (service-level and the 10 busiest endpoints: request rate, p95, error rate; database p95; connection-pool utilisation), sends them to the ML service (shared-secret header, 200 series per request), and upserts findings into `anomalies` (one active row per service × metric × scope; worse severity and higher score win; occurrences counted). Active anomalies not seen for 10 minutes are resolved. The ML service holds no data and no database credentials.

**Why:** Keeping tenant data behind the backend's RLS means the Python service cannot leak across tenants even if compromised, needs no database access, and can be scaled or replaced independently. Merging repeats prevents a single sustained problem from producing one anomaly per minute, which is what makes the later incident correlation (Phase 13) tractable.


## ADR-025 — Incident correlation by project with one active automatic incident (2026-11-24)

**Decision:** After every detection run, the backend correlates new (unlinked, open) anomalies per organization and project. A project has at most one active automatic incident (`dedup_key = auto:project:<id>`, enforced by the partial unique index from V5); new anomalies join it, or open one when at least two arrive together or one is high/critical — a lone low/medium finding stays an anomaly. The incident's severity is raised to the worst linked anomaly and never lowered automatically; its title (problem family in order of urgency: error rate › traffic drop › latency › connection pool › traffic spike, plus "with slow database") and its summary (one line per anomaly, worst first, with the reason) are regenerated only while the incident is `open` and nobody has acted on it. When all linked anomalies have ended, an `anomalies_recovered` event is recorded once and an incident still `open` is resolved automatically; an incident a person has moved to investigating/acknowledged is left for them to close. Every automatic step writes a timeline event with a null actor. Evidence (logs, slow and failing traces, slow statements) is gathered at read time from the existing explorers for the incident's period instead of being copied.

**Why:** The live slow-database test produced four simultaneous anomalies for one root problem; operators need one incident with the evidence attached, not four alerts. Grouping by project is simple, explainable and matches how teams own services; it is a deliberate first step (two unrelated problems in one project at the same time share an incident — KNOWN_ISSUES #35). Never overwriting human edits, never lowering severity automatically and never auto-resolving an incident someone is working on keep people in control. Reading evidence live keeps storage small and always consistent with retention.

## ADR-026 — Web app: same-origin API, in-memory access token, instrument-style design system (2026-11-24)

**Decision:** The React app (Vite, MUI 7, TanStack Query 5, React Router 7) calls the API at the same origin: in development Vite proxies `/api` to the backend; in deployment a reverse proxy will serve both (Phase 18). The access token is kept only in memory; the session survives reloads through the HttpOnly refresh cookie (`/auth/refresh` with the `X-PulseOps-Client` header), and a 401 triggers one shared refresh and a single retry. An unreachable server is reported as "cannot reach the server", not as a sign-out. Server state lives in TanStack Query (client errors are not retried); browser storage holds only the last-used organization. Permissions come from `GET /organizations/{id}/access` and only hide or disable controls — the backend remains the authority. Design tokens: ink sidebar, cool grey canvas, flat bordered panels without shadows or gradients, cobalt as the single action colour, green/amber/red reserved for health and always paired with a word; IBM Plex Sans for the interface and IBM Plex Mono only for machine text (keys, endpoints, code); one orchestrated animation (the pulse trace on the sign-in screens), disabled under reduced motion. Fonts are bundled through `@fontsource` (no runtime calls to a font CDN, so self-hosted installs work offline).

**Why:** Same-origin avoids CORS and keeps the SameSite=Strict refresh cookie working without loosening it; keeping the access token out of storage limits what an injected script could steal. An operations tool is read under pressure: colour that only ever means health, and status that is never colour alone, make the state of a system legible at a glance and accessible.
