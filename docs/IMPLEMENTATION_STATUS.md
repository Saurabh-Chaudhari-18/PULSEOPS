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
| 8 | Java Sample Application Integration | NOT STARTED |
| 9 | Application and API Monitoring | NOT STARTED |
| 10 | Database Monitoring | NOT STARTED |
| 11 | Logs and Distributed Traces | NOT STARTED |
| 12 | Python ML Service and Anomaly Detection | NOT STARTED |
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

**Tests executed on the developer PC (2026-11-21, JDK 25, IntelliJ "All in pulseops-backend", exit code 0 — no failures):** compiles against `opentelemetry-proto` 1.5.0-alpha / `protobuf-java` 4.28.3; Flyway applied V11 to the existing V10 database; `OtlpDecoderTest`, `OtlpIngestServiceTest`, the updated `TelemetryProcessorTest`, and the end-to-end `FullJourneyIntegrationTest.openTelemetryJavaAgentExportsBecomeTracesLogsDbCallsAndMetrics` (gzip trace export with partial rejection, logs, metrics, protobuf error bodies, CONNECTED, span dedupe, redaction, stored rows checked under RLS) all pass alongside every earlier test. The `db down` stack trace in the log is the expected output of `storageFailureIsCountedAndDoesNotMarkConnected`.

**Next:** Phase 8 (sample Spring Boot application instrumented with the Java agent) or Phase 12 (Python anomaly detection — fully testable here).
