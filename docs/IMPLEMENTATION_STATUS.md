# PulseOps — Implementation Status

Statuses: `NOT STARTED` · `IN PROGRESS` · `BLOCKED` · `COMPLETED`

Last updated: 2026-11-21 (Phases 3–6 completed)

## Phase Overview

| # | Phase | Status |
|---|---|---|
| 0 | Repository Inspection and Planning | COMPLETED |
| 1 | Project Foundation and Development Environment | COMPLETED |
| 2 | Database Schema and Migrations | COMPLETED |
| 3 | Authentication and Multi-Tenant Organization Management | COMPLETED |
| 4 | Projects, Services and Integration Management | COMPLETED |
| 5 | API Credentials and Integration Onboarding | COMPLETED |
| 6 | REST Telemetry Ingestion | COMPLETED |
| 7 | OpenTelemetry OTLP Ingestion | COMPLETED |
| 8 | Java Sample Application Integration | COMPLETED |
| 9 | Application and API Monitoring | COMPLETED |
| 10 | Database Monitoring | COMPLETED |
| 11 | Logs and Distributed Traces | COMPLETED |
| 12 | Python ML Service and Anomaly Detection | COMPLETED |
| 13 | Incident Correlation | NOT STARTED |
| 14 | Root Cause Analysis and Recommendations | NOT STARTED |
| 15 | Alerts and Notifications | NOT STARTED |
| 16 | Analytics Dashboard | NOT STARTED |
| 17 | Security and Performance Hardening | NOT STARTED |
| 18 | SaaS and Self-Hosted Deployment | NOT STARTED |
| 19 | Documentation and Final Product Validation | NOT STARTED |

---

## Phase 0 — Repository Inspection and Planning — COMPLETED

**Findings**
- `F:\PULSEOPS` on the development machine: empty → initialize monorepo (per Part 17.1).
- Cloud build workspace: Java 21 (OpenJDK 21.0.13), Maven, Gradle, Node 22.23/npm 10.9, Python 3.13, PostgreSQL 16.15 (local server), git, Docker CLI.
- Python available now: scikit-learn 1.9.1, pandas 3.0.5, numpy 2.5.3, scipy 1.18.1, joblib 1.6.0, pytest 9.1.1, pydantic 2.13.5, starlette 1.7, uvicorn 0.54. Missing: fastapi, httpx, sqlalchemy, psycopg2.
- **Constraint:** package registries (Maven Central, npm, PyPI) currently blocked in the build workspace → Java compilation and npm installs blocked until network capability is enabled (see KNOWN_ISSUES.md #1).

**Workflow decision:** implement and verify in the cloud workspace; sync repository to `F:\PULSEOPS` at each phase boundary. The 8 GB development machine is not required to run heavy builds.

---

## Phase 1 — Project Foundation — COMPLETED (2026-11-20)

**Files created:** README.md, .gitignore, docs/{PRD,ARCHITECTURE,DEVELOPMENT,IMPLEMENTATION_STATUS,DECISIONS,KNOWN_ISSUES}.md, backend/pom.xml, backend application.yml (base + dev + prod profiles), PulseOpsApplication.java, ml-service/requirements{,-dev}.txt + app skeleton, frontend/package.json + vite.config.ts + tsconfig.json.

**Tests executed:** none applicable (scaffold); Java/npm builds pending network capability (KNOWN_ISSUES #1).

**Acceptance:** monorepo structure per Part 14 in place; git on `main`; tracking docs live; dependency versions pinned per ADR-008 pending first online build.

**Next steps:** docs API.md/SECURITY.md/DEPLOYMENT.md/CONTRIBUTING.md are written in their owning phases (6–7, 17, 18) rather than as empty stubs.

---

## Phase 2 — Database Schema and Migrations — COMPLETED (2026-11-20)

**Files created:** `backend/src/main/resources/db/migration/V1–V8` (helpers, identity, application management, telemetry, intelligence, alerting+administration, RBAC seed, Row-Level Security), `scripts/verify_migrations.sh`, docs/DATABASE.md.

**Deliverables:** 27 tables across all six required groups; UUID PKs, FKs, CHECK constraints, partial unique indexes (soft deletes, active-incident dedup), BRIN time indexes, updated_at triggers, hashed-only secrets columns, 22 tables under FORCE RLS with fail-closed tenant policies, deterministic RBAC seed (11 permissions, 4 system roles).

**Tests executed:** `scripts/verify_migrations.sh` on PostgreSQL 16.15, applying all migrations as non-superuser `pulseops_app` — PASS. Assertions: schema inventory; seed catalog; cross-tenant write rejected by RLS; cross-tenant read returns 0 rows; queries without tenant context fail closed; org creation allowed pre-context while reads stay hidden; updated_at trigger; CHECK violations rejected (user status, error_count ≤ request_count); case-insensitive email uniqueness.

**Known issues:** retention cleanup jobs land in later phases (KNOWN_ISSUES #3); backend `SET LOCAL app.current_org_id` wiring lands in Phase 3 (KNOWN_ISSUES #4).

---

## Phase 3 — Authentication and Multi-Tenant Organization Management — COMPLETED (2026-11-21)

**Objectives:** registration with email verification, login, JWT access tokens, rotating refresh tokens with theft detection, logout/revocation, password reset, organization creation and management, team & role management, RBAC enforcement, tenant context wired into PostgreSQL RLS, audit logging.

**Completed tasks (code)**
- `common`: typed config (`PulseOpsProperties`), uniform errors (`ApiError`, `GlobalExceptionHandler`), security config (stateless JWT resource server, CORS allow-list, security headers, deny-by-default), JWT HS256 issue/validate with fail-fast secret validation, secure token generation + SHA-256 hashing, fixed-window rate limiter, pagination primitives, mail port (dev-log / SMTP, fail-fast when misconfigured, async + after-commit dispatch).
- `common.tenancy`: `TenantContext`, `TenantAwareDataSource` (sets org + user settings on every pooled checkout), `TenantContextInterceptor` (membership + `@RequiresPermission` enforcement, 404 for non-members), `OrgAccess` argument resolver.
- `auth`: register / verify-email / resend / login / refresh (rotation + reuse detection) / logout / forgot / reset; change password and sign-out-everywhere; HttpOnly SameSite=Strict refresh cookie + CSRF header guard; per-IP/per-email rate limits; enumeration-resistant responses.
- `user`: profile read/update, password policy.
- `organization`: create (creator → OWNER, RLS-safe ID-first creation), list mine, get, update, soft delete with slug confirmation; members list/add/change role/remove/leave with owner-only escalation and last-owner protection under row locks.
- `rbac`: system role catalog, per-request access resolver, `/access` and `/roles` endpoints.
- `audit`: transactional audit writer (org always from tenant context) + paginated, filterable audit log endpoint.
- Migration **V9** user-scoped RLS policies.

**Files created:** ~60 main classes across `common`, `auth`, `user`, `organization`, `rbac`, `audit`; `V9__user_scoped_rls.sql`; 15 test files (~60 test methods); docs `API.md`, `SECURITY.md`.
**Files modified:** `pom.xml` (+spring-boot-starter-mail), `application.yml`, `PulseOpsApplication.java`, `scripts/verify_migrations.sh`, DATABASE / DEVELOPMENT / DECISIONS (ADR-009…013) / KNOWN_ISSUES.

**Tests executed**
- ✅ `scripts/verify_migrations.sh` (V1–V9) on PostgreSQL 16.15 — PASS, including new user-scope assertions.
- ✅ Every SQL statement embedded in the Java services executed against PostgreSQL under the same tenant/user contexts the code sets (access resolver, org listing, member listing, role catalog, audit insert/list, platform vs tenant audit rows) — correct results; forged platform audit row from a tenant context rejected by RLS.
- ✅ javac parser over all 91 Java files — 0 syntax errors.
- ✅ Full javac attribution without dependencies — every remaining error is attributable to absent external libraries (Spring/JPA/AssertJ/Mockito); 0 errors in references between PulseOps classes (ADR-013).
- ✅ **First real JUnit run (2026-11-21, developer PC, IntelliJ 2025.3, JDK 25, Spring Boot 3.5.6) on commit 129a6b1:** all main code **compiled against the real Spring/Hibernate/Nimbus libraries**; 69 tests: 65 passed, 2 skipped by design (`TenantIsolationIntegrationTest`, no `PULSEOPS_TEST_DB_URL`), **2 failed → fixed**:
  - `JwtServiceTest`: reported `expiresAt` kept nanoseconds while the JWT `exp` claim is whole seconds → real (minor) bug; `JwtService` now truncates to seconds.
  - `MemberServiceTest`: test misuse — a mock's getter was called inside another stubbing's `thenReturn` (`UnfinishedStubbingException`); role fixtures are now real `Role` objects.
- ✅ **Second run (commit 4f062fe, same environment): 181 tests — 179 passed, 0 failed, 2 skipped** (DB-gated). Covers every unit test of Phases 3–6. (The `ERROR … db down` log line comes from `TelemetryProcessorTest` simulating a storage outage on purpose.)
- ✅ **Third run (commit 113e28a) with PostgreSQL 16.15 in Docker — first real application boot:** Flyway applied V1–V10 to an empty database; Hibernate `validate` passed for every entity; both security chains and the telemetry processor started. 179 unit tests passed. **3 DB-only failures, fixed:**
  - **Real bug — registration and organization creation:** `AuditService` writes with JDBC immediately, but the user/organization it references (FK) was still pending in the JPA persistence context → FK violation → 409. Fix: `AuditService.record` flushes pending JPA changes first (regression test `AuditServiceTest`).
  - Test bug: `TenantIsolationIntegrationTest` ran Flyway on its deliberately single-connection pool; Flyway needs two connections → 30 s timeout. It now migrates through a separate connection.
- ✅ **Fourth run (commit 72cb9fc): `FullJourneyIntegrationTest` passed** — full application on PostgreSQL: register → login → org → project → service → key → REST telemetry → CONNECTED, redaction and route templating verified in the stored rows, RLS hides data without context, cross-tenant 404, revoked key 401, JWT rejected on ingest, refresh rotation + reuse revocation, CSRF header, actuator exposure. `TenantIsolationIntegrationTest`: RLS **correctly rejected** the cross-tenant write, but the assertion read Spring's wrapper message ("bad SQL grammar") instead of PostgreSQL's root cause → assertion fixed.
- ✅ **Fifth run (commit fe71e51) — ALL GREEN, exit code 0:** every unit test plus `TenantIsolationIntegrationTest` and `FullJourneyIntegrationTest` against PostgreSQL 16.15. **Phases 3, 4, 5 and 6 acceptance criteria met → COMPLETED.**
- (historical) Remaining for COMPLETED: re-run of the DB-gated tests (`TenantIsolationIntegrationTest`, new `FullJourneyIntegrationTest` which also proves application boot + Hibernate validation) against PostgreSQL — see DEVELOPMENT.md. Previously listed as not yet run: JUnit/Mockito unit tests (rate limiter, tokens, JWT round-trip/forgery/expiry, password policy, slugs, refresh rotation & reuse detection, login/registration enumeration defences, password reset, member role invariants, tenant interceptor) and `TenantIsolationIntegrationTest` (real RLS through a single-connection pool). Application boot (Hibernate `validate`) also unverified.

**Acceptance criteria (Phase 3 is COMPLETED only when all pass)**
1. `mvn test` green, including `TenantIsolationIntegrationTest` against PostgreSQL. ⏳
2. App boots with `dev` profile; Flyway applies V1–V9; Hibernate validation passes. ⏳
3. Manual journey from DEVELOPMENT.md works: register → dev-mail link → verify → login → create org → refresh rotates cookie → reused refresh token revokes sessions. ⏳
4. Cross-tenant access returns 404 / RLS rejects cross-tenant writes. ✅ (DB level) ⏳ (HTTP level)

**Pending tasks:** run the build + tests (blocked by KNOWN_ISSUES #1); invitation emails for unregistered users (deferred); scheduled cleanup of expired tokens (Phase 17).

**Next steps:** once a build is possible, fix any compile/test failures, then mark Phase 3 COMPLETED and start Phase 4 (projects & services). Phase 4 work can proceed code-first in parallel if builds remain blocked.

---

## Phase 4 — Projects, Services and Integration Management — COMPLETED (2026-11-21)

**Completed (code):** `project` module (create/list+search/get/update/archive/restore/soft delete with name confirmation; service & connected-service counts), `appservice` module (services per project and org-wide; CRUD, archive/restore, soft delete; connection state in every view), case-insensitive enum input, escaped LIKE search (`LikePatterns`), domain events (`ProjectDeactivated`, `ServiceDeactivated`) so deletions cascade to services and credentials in the same transaction without circular dependencies.

**Tests executed:** every list/detail SQL statement run on PostgreSQL under tenant context (counts, filters, literal `%`/`_` search, case-insensitive unique names with soft-delete reuse, cross-tenant invisibility) ✅. Offline javac checks ✅. Unit tests written (`ProjectServiceTest`, `ServiceCatalogServiceTest`, `ProjectDetailsTest`, `LikePatternsTest`) ⏳ not executed.

**Acceptance (pending build):** CRUD via HTTP with RBAC; archived project blocks new services/keys/telemetry; deleting a project revokes its keys.

## Phase 5 — API Credentials and Integration Onboarding — COMPLETED (2026-11-21)

**Completed (code):** `credential` module (key format, hashed storage, create-once, list, revoke, rotate with grace, expiry, last-used throttling, revocation on project/service deletion, `CredentialAuthenticator` using the V10 hash-scoped policy); `integration` module (connection lifecycle NOT_CONNECTED → WAITING_FOR_DATA → CONNECTED only on real telemetry; CONNECTION_ERROR; DISABLED; verification status with `receivingNow`/`nextStep`; installation guides for REST (available) and Java agent/OTLP (marked unavailable until Phase 7)). Migration **V10**.

**Tests executed:** V10 policy behaviour on PostgreSQL (only the presented hash's row visible, setting cleared at commit, wrong hash sees nothing, hash context cannot write) ✅ — now permanent assertions in `verify_migrations.sh`. Connection-status upserts (first telemetry, 30 s write throttle, error → connected → error-while-connected, disabled never re-enabled) ✅. Unit tests written (`IntegrationCredentialTest`, `CredentialServiceTest`, `CredentialAuthenticatorTest`, `IntegrationConnectionTest`, `IntegrationServiceTest`) ⏳.

## Phase 6 — REST Telemetry Ingestion — COMPLETED (2026-11-21)

**Completed (code):** `POST /api/v1/ingest/v1/events` on a dedicated security chain; key from `X-PulseOps-Key` or `Bearer`; per-key rate limit; stream-enforced body limit; JSON parsing; tenant-scoped service resolution (no auto-creation, scope checks, archived/disabled refusal); `EventNormalizer` (all-or-nothing validation with indexed errors, normalization, truncation); `SensitiveDataRedactor`; `RouteTemplates`; `ApiMetricAggregator` + `LatencyHistogram` (ADR-014); bounded async `TelemetryProcessor` (ADR-016) writing through the `TelemetryStore` port (`JdbcTelemetryStore`: raw events, merged minute aggregates, error logs) and marking connections CONNECTED.

**Tests executed**
- ✅ **Compiled and executed** (JDK-only classes): `RouteTemplates`, `SensitiveDataRedactor`, `LatencyHistogram`, `EventNormalizer`, `ApiMetricAggregator` — 81 assertions, all passing.
- ✅ Storage SQL on PostgreSQL: event insert with JSONB attributes, error-log insert satisfying trace/span CHECKs, cross-tenant insert rejected by RLS, histogram-merging upsert, range aggregation with `pulseops_histogram_sum`.
- ✅ Offline javac checks over 159 files.
- ⏳ JUnit: `IngestServiceTest`, `TelemetryProcessorTest`, `BoundedBodyTest`, `IngestControllerTest` + JUnit ports of the executed checks.

**Acceptance (pending build):** sample batch → 202 → rows in `telemetry_events`/`api_metrics`/`application_logs` → service CONNECTED; revoked/expired/invalid keys 401; oversize 413; invalid events 400 with field paths; cross-service/tenant writes impossible.

## Phase 7 — OpenTelemetry OTLP Ingestion — COMPLETED (2026-11-21)

**Completed (code):** OTLP/HTTP binary-protobuf receiver at `/otlp/v1/traces|logs|metrics` (ADR-019) using `io.opentelemetry.proto:opentelemetry-proto:1.5.0-alpha`.
- `otlp.decode`: `OtlpPayload` (identity/gzip, 16 MiB decompression cap), `OtlpDecoder` (ID/timestamp validation, AnyValue conversion, kind/status mapping, per-resource grouping).
- `otlp.mapping` (protobuf-free): `OtlpAttributes` (limits, redaction, SQL sanitizing, command-line dropping), `SqlSanitizer`, `SpanAnalyzer` (SERVER span → API request; DB client span → `database_operations`; `exception` events → ERROR logs; old and new semantic conventions), `LogLevels`, `MetricPointAggregator` (one-minute series, 2000-series cap).
- `otlp.service.OtlpIngestService`: key auth + shared per-key rate limit, resource → service attribution (service-scoped vs project-wide keys, never auto-create), one batch per service, queue-capacity check, `partial_success` reporting, Java-agent source detection.
- `otlp.api`: `OtlpController` (protobuf-only, `google.rpc.Status` errors, `Retry-After`), `OtlpStatus`.
- Shared with REST: `IngestTargetResolver` and `IngestRateLimiter` extracted from `IngestService`; `IngestBatch` extended with spans/logs/DB operations/metric points; `TelemetryStore` gained `saveSpans`, `saveLogs`, `saveDbOperations`, `mergeMetricPoints`.
- Migration **V11**: per-tenant span uniqueness (ADR-018), `metric_points` table with RLS and retention entry.
- Java-agent and OTLP installation guides enabled (gzip + `http/protobuf`).

**Tests executed (cloud)**
- ✅ `verify_migrations.sh` on PostgreSQL 16 with new V11 assertions: same trace/span ID accepted for two tenants, retry ignored within a tenant, metric merge (`3/350/50/200/200`), `metric_points` isolated by RLS.
- ✅ All four new store statements `PREPARE`d and executed on PostgreSQL (types and CHECK constraints satisfied).
- ✅ Compiled and executed (JDK-only, real JUnit test sources with a minimal runner): `OtlpMappingTest` (52 assertions), `OtlpPayloadTest` (6, incl. 48 KB → 50 MB gzip bomb stopped), `OtlpStatusTest` (12, wire bytes checked by hand).
- ✅ Offline javac checks over 183 files: no errors except absent external libraries.

**Tests executed on the developer PC (2026-11-21, JDK 25, IntelliJ "All in pulseops-backend", **223/223 tests passed**, 9.3 s):** compiles against `opentelemetry-proto` 1.5.0-alpha / `protobuf-java` 4.28.3; Flyway applied V11 to the existing V10 database; `OtlpDecoderTest`, `OtlpIngestServiceTest`, the updated `TelemetryProcessorTest`, and the end-to-end `FullJourneyIntegrationTest.openTelemetryJavaAgentExportsBecomeTracesLogsDbCallsAndMetrics` (gzip trace export with partial rejection, logs, metrics, protobuf error bodies, CONNECTED, span dedupe, redaction, stored rows checked under RLS) all pass alongside every earlier test. The `db down` stack trace in the log is the expected output of `storageFailureIsCountedAndDoesNotMarkConnected`.

## Phase 8 — Java Sample Application Integration — COMPLETED (2026-11-23)

**Completed (code):** `examples/order-service` — Spring Boot 3.5.6 order service with no observability code: order reads/lists/creation (JPA on H2), an inventory API called over real HTTP (CLIENT→SERVER trace hop), a simulated payment provider (402 + WARN log above a limit), unknown SKUs (422), validation errors (400), a sales report query, a built-in asynchronous traffic generator (~2 req/s), and runtime fault injection (`/admin/chaos`: `latency`, `errors`, `slow-db` via an H2 sleeping function so the delay is on the JDBC span, `outage`). Loopback-only by default because the chaos endpoint is unauthenticated.
- `run-with-agent.ps1` / `.sh`: download a pinned OpenTelemetry Java agent (2.32.0) on first use and start the app with the standard `OTEL_*` settings from the PulseOps installation guide (http/protobuf, gzip, `x-pulseops-key`).
- `scripts/dev-bootstrap.ps1` / `.sh`: create or reuse the demo account, organization, project and service, select the Java-agent integration and issue a service-scoped key; `scripts/dev-status.ps1` shows the connection status.
- README with the full Windows walkthrough and SQL to inspect stored traces, API metrics, DB operations, logs and metrics before the dashboards exist.

**Tests executed (cloud):** offline javac checks over the sample's 18 files (no errors beyond absent libraries); `dev-bootstrap.sh` run against a mock of the PulseOps API (first run creates, second run reuses, wrong password fails cleanly with exit 1).

**Acceptance run on the developer PC (2026-11-23, JDK 25, Windows):** backend on a fresh `pulseops_dev` database (Flyway V1–V11); `dev-bootstrap.ps1` created account → organization → project → service → JAVA_AGENT integration → service-scoped key; sample built with Maven `package` (its tests run in that step); started with OpenTelemetry Java agent 2.32.0 via `run-with-agent.ps1`. Result: integration status **CONNECTED**, `receivingNow=true`, and `api_metrics` populated from agent spans for all four routes (`/api/orders/{id}` 147, `/api/orders` 144, `/internal/inventory/{sku}` 88, `/api/orders/report` 25 requests in the first minutes; 0 server errors with faults off — 404/402/422 are client errors by design). The agent's parametrized `http.route` was used, so no high-cardinality paths.

**Notes from the run:** ports 8080 (Apache httpd) and 8090 (another desktop app) were taken on the PC, so the backend ran on `PULSEOPS_PORT=18080`; `dev-bootstrap.ps1` now prints `-BaseUrl/-OtlpEndpoint` hints for non-default ports. While the backend was down the agent logged connection-refused export failures and dropped that data (expected exporter behaviour).

## Phase 9 — Application and API Monitoring — COMPLETED (2026-11-23)

**Completed (code):** `monitoring` module (ADR-020).
- `TimeWindow`: from/to/step parsing, alignment, limits (31 days, ≤ 1440 points, automatic step for ≤ 360).
- `ApiMonitoringService` over `api_metrics`: service summary (requests, req/s, 5xx/4xx, error rate, avg/p50/p95/p99/max from merged histograms, status classes, connection, health), time series for a service or one endpoint (gap-filled; empty buckets have null latency), endpoint list with 7 sort orders, search and limit, and the organization overview ("what needs attention": totals from the org-wide merged histogram, services by health, worst first).
- `RuntimeMetricsService` over `metric_points`: JVM CPU, heap used/limit (summed over heap pools), threads, loaded classes — null when not reported.
- `HealthRules`: NO_DATA / HEALTHY / WARNING / CRITICAL with explanations; `MonitoringSql` keeps all queries in one place.
- `MonitoringController`: 5 read endpoints behind `telemetry.read` (API.md).

**Tests executed (cloud):**
- ✅ All 8 monitoring queries prepared and executed as `pulseops_app` under RLS on PostgreSQL 16 with fixture rows; results checked by hand (e.g. 140 requests / 10 server / 10 client errors; histograms merged across routes and minutes; 5-minute series buckets; heap = Eden 100 MB + Old 50 MB with non-heap excluded; CPU 0.2).
- ✅ `TimeWindowTest` (15 checks) and `HealthRulesTest` (18 checks, incl. hand-computed p50/p95/p99 from a histogram) compiled and executed.
- ✅ Offline javac checks over 194 files.

**Tests executed on the developer PC (2026-11-23):** **233/233 passed** (11.7 s), including `ApiMonitoringServiceTest`, `TimeWindowTest`, `HealthRulesTest` and the extended OTLP journey (summary, endpoints, endpoint series, runtime heap, overview, 400 on a bad range, 404 for a non-member).

**Acceptance on live data (sample service + Java agent, `scripts/dev-monitoring.ps1`):**
- `errors` fault → service CRITICAL, reason "Server error rate 20.1 %"; only `POST /api/orders` and `GET /internal/inventory/{sku}` carried errors (47.8 % each), 4xx reported separately.
- `slow-db` fault → CRITICAL, reason "p95 latency 1930 ms" with 0 % errors; `/api/orders/report` and `/api/orders/{id}` at ~1.5–2 s and first in the p95 sort, other endpoints unaffected.
- After `normal`, the last-2-minutes window was HEALTHY (p95 10 ms).
- JVM CPU, heap and thread counts reported from agent metrics.
- Observed limitation: when nearly all requests sit just above a bucket bound (e.g. 1.5 s), p50/p95/p99 can coincide at the observed maximum — expected histogram resolution (ADR-014).

## Phase 10 — Database Monitoring — COMPLETED (2026-11-23)

**Completed (code):** `DatabaseMonitoringService` + `DatabaseMonitoringController` (ADR-021): database summary with breakdown by system × operation type and health; query-shape table with share of database time, slow-call counts, exact percentiles, last seen and the slowest call's trace ID; per-statement or overall time series; individual slow/failed calls with trace and span IDs; connection-pool gauges (used/idle/max/pending/peak) under both metric naming conventions; database totals in the organization overview. `HealthRules` generalised to per-signal thresholds (HTTP and database). `scripts/dev-monitoring.ps1` now shows the database section.

**Tests executed (cloud):**
- ✅ All 8 database queries prepared and executed as `pulseops_app` under RLS on PostgreSQL 16 with fixture calls and pool metrics; results checked by hand (5 calls / 1 error / 2 slow; exact p95 1580 ms; query shapes ordered by total time with the slowest trace ID; 5-minute series; slow-call and errors-only listings; pool used 4 avg / 6 peak / idle 8 / max 10 from the older Hikari metric names).
- ✅ `HealthRulesTest` extended (24 checks incl. database thresholds) compiled and executed.
- ✅ Offline javac checks over 199 files.

**Tests executed on the developer PC (2026-11-23):** **237/237 passed** (10.9 s), including `DatabaseMonitoringServiceTest` and the extended OTLP journey (database summary, `slowMs`, operations with share and trace link, calls, series, pool unavailable, invalid sort → 400, overview database totals).

**Acceptance on live data (sample + Java agent, `slow-db` fault, last 5 minutes):** database health CRITICAL ("p95 latency 1514 ms"), 623 calls, 168 slow; `SELECT PULSE_SLEEP(?)` first with 99.9 % of database time (avg 1509 ms) while the Hibernate order queries stayed at 0–1 ms; statements shown with literals removed; HikariCP pool gauges received from the agent (used ≈ 1.25, idle ≈ 8.75, max 10, waiting 0); the HTTP view simultaneously showed the two affected endpoints (`/api/orders/{id}`, `/api/orders/report`) at ~1.5 s.

## Phase 11 — Logs and Distributed Traces — COMPLETED (2026-11-23)

**Completed (code):** `explore` module (ADR-022).
- `LogExplorerService`: search by time, service, minimum or exact levels, message text and trace ID, newest first with keyset cursors; per-level volume histogram.
- `TraceExplorerService`: trace search (recent with cursors, or slowest), filters for service, minimum duration, errors only and root operation; single trace as a depth-first span tree with offsets, orphans, attributes and the trace's logs.
- `TraceTree` (depth-first ordering, orphan roots, cycle-safe) and `Cursor` (opaque, validated).
- `ExplorerController` (4 endpoints, `telemetry.read`); `scripts/dev-explore.ps1` prints recent warnings/errors, the slowest traces and the slowest trace's tree.

**Tests executed (cloud):**
- ✅ All 5 explorer queries prepared and executed as `pulseops_app` under RLS on PostgreSQL 16 with fixture spans and logs: pagination resumes exactly after the cursor row; level/text/trace filters; histogram per level; recent vs slowest ordering; error-only, duration and name filters; trace cursor; a trace with an unreceived root reported `has_root=false`; another tenant's span with the **same trace ID** never appears in counts or trees.
- ✅ `CursorAndTreeTest` (9 checks: micro-second cursor round trip, tampered cursors, depth-first order, orphans, cycles) compiled and executed.
- ✅ Offline javac checks over 209 files.

**Tests executed on the developer PC (2026-11-23):** **244/244 passed**, including `ExplorerServicesTest` and the extended OTLP journey (trace list and filters, trace tree with offsets and logs, log search with level + text, cursor paging, histogram, 400s for bad trace ID/cursor, 404 for unknown and foreign traces).

**Acceptance on live data (`scripts/dev-explore.ps1`):**
- `errors` fault: each failed order produced four ERROR logs (inventory lookup failed → exception → servlet error → "Could not place order"), all carrying the same trace ID.
- Normal traffic, last 3 minutes: full trees, e.g. 8 spans / 268 ms — traffic CLIENT `POST` → SERVER `POST /api/orders` → CLIENT `GET` → SERVER `GET /internal/inventory/{sku}`, then `OrderRepository.save` → `Session.persist` → CLIENT `INSERT orders.orders` → `Transaction.commit`, with the trace's INFO log attached; WARN "Payment declined" logs linked to their traces.
- A first run during heavy machine load (tests + backend restart on the 8 GB PC) showed ~39 s client spans and traces missing middle spans: the sample JVM was stalled and the agent dropped exports while PulseOps restarted. The explorer displayed it faithfully (orphan roots); a re-run once the machine was idle was normal.
- Follow-up done: trace summaries gained `entryName` (first SERVER span) because the sample's self-generated traffic makes HTTP-client spans (named only `POST`/`GET`) the roots; `name` search matches either. Verified on PostgreSQL.

## Phase 12 — Python ML Service and Anomaly Detection — COMPLETED (2026-11-24)

**Completed (code):**
- `ml-service` (FastAPI, numpy, pydantic; ADR-023): robust median/MAD detection with per-metric practical-significance guards, severities and explanations; `POST /v1/detect` (shared secret, constant-time compare, 4 MiB body limit, ≤ 200 series × 1440 points, strict validation, docs endpoints disabled); refuses to start without a ≥ 32-character secret unless explicitly in insecure dev mode; Dockerfile (non-root); README with rules.
- Backend `anomaly` module (ADR-024): `AnomalyDetectionJob` (minute schedule; cross-tenant discovery via the V12 SELECT-only `app.discovery` policy; per-tenant series building for service + top-10 endpoints, database p95 and pool utilisation; upsert/merge; auto-resolve after 10 min), `MlClient`, `SeriesBuilder`, `AnomalyService` + `AnomalyController` (list with filters and cursors, detail, dismiss with audit, detection status).
- Migration **V12**: discovery policy; anomalies gain scope, reason, occurrences, last_seen_at, resolved_at, status `resolved`; one active anomaly per service × metric × scope.
- `scripts/dev-anomalies.ps1`.

**Tests executed (cloud):**
- ✅ Python: 19 tests — detection (latency/DB spikes with severity and reason, noise and small absolute changes ignored, single bad minute ignored, sparse minutes ignored, insufficient history reported, error rate from a zero baseline and its minimum failed-request count, traffic drop with missing minutes, low-traffic drops ignored, traffic spike, sensitivity levels, flat-baseline spread floor, non-finite values), request validation/limits, and the backend JSON contract. The FastAPI HTTP tests are skipped here (FastAPI not installable in the cloud sandbox).
- ✅ `verify_migrations.sh` with V12 assertions: discovery sees both tenants only with the setting, cannot write, and the setting does not survive the transaction; anomaly upsert keeps the worse severity/higher score and counts occurrences; a resolved anomaly does not block a new one.
- ✅ All 11 anomaly queries prepared and executed on PostgreSQL 16 (discovery, per-minute service/endpoint/database/pool series, upsert insert vs merge, severity filter, stale resolution, dismiss).
- ✅ Offline javac checks over 216 files.

**PC run 1 (2026-11-24):** backend **249/249 passed** (incl. `AnomalyUnitTest` and the journey's detection step against the stub ML service). Python on Windows (3.12, FastAPI 0.165): 24/25 — the oversize-body test used a body below its own 10 KB test limit. Review of that test exposed a real gap: the limit only checked `Content-Length`, so a chunked request had no limit. Fixed with a pure-ASGI `BodyLimitMiddleware` that counts streamed bytes and replays the buffered body (3 ASGI-level tests run here; a chunked-request API test added).

**Live acceptance (2026-11-24, sample + Java agent + real ML service on Windows):** after the detection status gained skip reporting, the `slow-db` fault produced exactly four CRITICAL anomalies — `db_duration` (1514 ms vs usual ~1 ms), `response_latency` for `GET /api/orders/{id}` (1563 ms vs 16 ms) and `GET /api/orders/report` (1542 ms vs 39 ms), and the service-level p95 (1563 ms vs 32 ms) — while unaffected endpoints, error rates and traffic stayed quiet; the new `PUT /admin/chaos` series were reported as "insufficient history" instead of judged; a repeat run merged into the same anomalies (`occurrences` 2). Reason formatting now keeps a decimal below 10 ms ("usual 0.7 ms").

**Final PC results (2026-11-24):** backend **250/250 passed**; ML service **30/30 passed** on Windows (Python 3.12, FastAPI 0.165; one Starlette deprecation warning, KNOWN_ISSUES #34). After the fault was switched off detection reported 0 findings and the four anomalies moved to `resolved` 10 minutes after they were last seen (occurrences 6–7 while the fault lasted).

**Next:** Phase 13 (incident correlation).

## Phase 13 — Incident Correlation and Incident Management — COMPLETED (2026-11-24)

**Completed (code):**
- `IncidentCorrelator` (ADR-025), run after every detection pass per organization: new anomalies join their project's active automatic incident or open one (≥ 2 anomalies, or one high/critical); severity raised to the worst anomaly; title/summary regenerated while nobody has acted; `anomalies_recovered` event and automatic resolution of untouched incidents once all linked anomalies have ended. Detection status reports `incidentsCreated`.
- `IncidentRules` (problem family, title, summary, threshold) and `IncidentTransitions` (lifecycle) as pure classes.
- `IncidentService` + `IncidentController`: list (status/severity/project/service filters, window overlap, cursors), detail with linked anomalies, timeline, comments and evidence gathered live (WARN+ logs, slowest and failing traces, slowest statements for the incident's period and services), manual creation, PATCH (status transitions, severity, assignment to active members, title/summary/notes) with a timeline event per change and audit, comments (author-only delete).
- Anomaly endpoints now require `incident.read` / `incident.manage` (they are incident-intelligence data).
- Migration **V13**: new incident event types; correlation and list indexes. Events use `clock_timestamp()` so the timeline keeps the order of steps within one transaction.
- `scripts/dev-incidents.ps1` (list, detail with timeline and evidence, acknowledge/resolve).

**Tests executed (cloud):**
- ✅ `IncidentRulesTest` (5 tests, 27 checks: severities, threshold, titles for each family, summary ordering, all allowed and refused transitions) run with a JDK-only harness.
- ✅ All 25 incident queries prepared and executed on PostgreSQL 16 as the app role under RLS: correlation (unlinked anomalies, dedup conflict returns no row, idempotent linking, refresh only while untouched, recovery detection only after the last link, automatic resolution), list filters/cursor, detail parts, manual creation, every lifecycle update (acknowledged/resolved/reopen/closed timestamps), comments (other user's delete refused).
- ✅ `verify_migrations.sh` with V13 assertions (dedup, event types, isolation) — V1–V13 PASS.
- ✅ Offline javac checks over 225 files.
- ⏳ To run on the PC: `IncidentRulesTest`, `IncidentServiceParamsTest`, and journey step 10 (automatic incident from the stub's high finding, no duplicate on repeat, detail/timeline, self-resolution after the anomaly is dismissed, manual incident lifecycle, assignment, comments, 409/400/404 cases, cross-tenant 404).

**Live acceptance (2026-11-24, sample + Java agent + real ML service on Windows):** the `slow-db` fault opened exactly **one** automatic incident, critical, titled "Latency increase on order-service (database slower)". It linked the four critical anomalies (service p95, `GET /api/orders/{id}`, `GET /api/orders/report`, database p95 1514 ms vs usual 1.2 ms) plus a later low pool-utilisation finding, which joined the same incident 4 minutes afterwards. The summary listed them worst first; the timeline showed creation and each link by PulseOps. The evidence window (14:13–14:31) put `SELECT PULSE_SLEEP(?)` first among slow statements (p95 1515 ms, 627 calls), with the slow report traces. Findings: (1) the pool finding at 20 % was noise → pool findings now need ≥ 50 % (ML test added; KNOWN_ISSUES #39); (2) evidence logs were the sample's routine "Payment declined" warnings, unrelated to the fault — ranking evidence by relevance belongs to root-cause analysis (Phase 14). 

**Final PC results (2026-11-24):** backend **257/257 passed** (incl. `IncidentRulesTest`, `IncidentServiceParamsTest` and journey step 10, which exercises automatic resolution end to end against PostgreSQL); ML service **31/31 passed** with the pool-utilisation floor. Automatic resolution after the live fault was switched off was not separately observed on the PC; it is covered by the journey test and will be seen again in the frontend work.

**Next:** frontend for Phases 1–13 (in parts), then Phase 14 (root-cause analysis).

## Frontend part 1 — App shell, accounts, organizations, projects, services, onboarding — COMPLETED (2026-11-24)

**Completed (code), ADR-026:**
- Foundation: Vite config (proxy via `PULSEOPS_BACKEND_URL`), design tokens/theme, bundled IBM Plex fonts, API client (in-memory token, shared refresh, retry once, typed errors with field messages), auth context (session restore on load, offline state), route guards, app layout (sidebar by permission, organization switcher, account menu, responsive drawer).
- Screens: sign in (incl. unverified-email resend), create account (local password rules mirror the backend; verification-pending state), forgot/reset password, verify email; first-organization setup; projects list (search, active/archived, connection summary) and create; project page (services with connection status, integration keys with create-once/revoke, archive/restore/delete with typed confirmation); service page with a four-step connection guide (method, key, instructions from the backend, live verification polling every 5 s) plus ingestion on/off and archive; team (roles with descriptions, add, change role, remove, leave — owner-only rules respected in the UI); account settings (name, password change, sign out everywhere).

**Tests executed (cloud):**
- ✅ TypeScript strict check of all sources against generated stubs of the libraries (React hooks and TanStack Query typed precisely, so result narrowing and data flow are checked; MUI/router props are not).
- ✅ 10 unit tests (Vitest specs, run in Node with a shim): token sent; refresh-and-retry once; one shared refresh for concurrent 401s; failed refresh signs out and surfaces the original error; field errors; unreachable server reported as NETWORK without signing out; 204 handling and query building; time formatting; connection states; password rules.
- ⏳ On the PC: `npm install`, `npm run typecheck`, `npm test`, `npm run dev`, and a walk through the screens against the running backend.

**PC results (2026-11-24, Node 26.13, Windows):** `npm run typecheck` clean (after one fix: the Web Locks typing differs under TypeScript 5.9); `npm test` 10/10 on Vitest 4.1.12; `npm audit` 0 vulnerabilities (Vitest 3 advisories fixed by the upgrade, KNOWN_ISSUES #42); `npm run dev` on Vite 7.3.8 against the backend on 18080. Signed in with the demo account; the projects list, the Shop project (service, integration key) and the order-service connection page rendered with live data. Review found one inconsistency (green "Connected" in the list vs "no recent data" on the service page) — fixed (#43). Not yet walked through on the PC: registration, team and account screens.

**Next:** frontend part 2 (overview, API and database monitoring, logs, traces, anomalies, incidents).

## Frontend part 2a — Overview, service performance, database — CODE COMPLETE, awaiting PC run (2026-11-24)

**Completed (code):**
- Shared: time-range presets kept in the URL (`?range=15m|1h|6h|24h|7d`; live refresh every minute for ranges up to 6 h, every 5 minutes beyond; previous data stays on screen, dimmed, while refetching), stat tiles, health signal, a line-chart component (one y-axis, 2px lines, gaps for absent data — never drawn as zero, crosshair tooltip listing every series, legend for 2+ series, a table view of every chart). Series colours validated with the dataviz palette checker on the white chart surface (adjacent CVD ΔE ≥ 9.2; the third colour is below 3:1 contrast, covered by the legend and table view).
- Overview (new landing page): range and project filters, service counts by health, requests, server error rate, p95, database p95/slow/failed calls, "needs attention" with the backend's health reasons, all services with health, throughput, errors, p95 and last data.
- Service page with tabs: **Performance** (health banner with reasons, or a "no requests" hint linking to the connection guide; throughput, 5xx rate and p50/p95/p99 stats; latency, throughput and error-rate charts; endpoints table with route filter and sort, selecting an endpoint focuses the charts; JVM runtime charts — CPU, heap used vs limit, threads — when exported), **Database** (health, calls/s, p95, slow and failed calls; duration and call-rate charts; connection-pool chart when reported; statements with share of database time, sort and filter, selecting one focuses the charts; slow or failed individual calls), **Connection** (the part 1 guide).
- Navigation: "Observe › Overview"; `/o/{org}` opens the overview; services without data open on their connection tab.

**Tests executed (cloud):** strict TypeScript check against library stubs (TanStack Query typed precisely); 15 unit tests (client, formats, number formatting: absent data is a dash, latency/percent/rate/count/byte scales); Prettier formatting (`npm run format` added).
