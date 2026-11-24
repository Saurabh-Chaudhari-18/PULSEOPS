# PulseOps — API Reference

Base path: `/api/v1`. JSON everywhere. Authenticated endpoints take `Authorization: Bearer <accessToken>`.

## Conventions

- **Errors** — every failure returns:
  ```json
  { "timestamp": "...", "status": 403, "code": "MISSING_PERMISSION",
    "message": "Your role does not grant the 'project.manage' permission",
    "path": "/api/v1/...", "fieldErrors": [ { "field": "email", "message": "must be a well-formed email address" } ] }
  ```
  `code` is stable and machine-readable; `message` is safe to show users. `fieldErrors` appears only on `VALIDATION_FAILED`.
- **Pagination** — list endpoints accept `page` (0-based, default 0) and `size` (default 25, max 100) and return
  `{ "content": [...], "page": 0, "size": 25, "totalElements": 42, "totalPages": 2 }`.
- **Organization scope** — routes under `/organizations/{orgId}/...` require an *active* membership. Non-members receive **404 `ORGANIZATION_NOT_FOUND`** (existence is not revealed). Insufficient role → **403 `MISSING_PERMISSION`**.
- **Rate limits** — exceeded limits return **429 `RATE_LIMITED`**.

---

## Authentication (`/auth`, public)

The refresh token is never in a response body: it is set as cookie `pulseops_refresh` (HttpOnly, Secure, SameSite=Strict, Path=/api/v1/auth). `/auth/refresh` and `/auth/logout` additionally require header **`X-PulseOps-Client: web`** (CSRF defence).

| Method & path | Body | Success | Notable errors |
|---|---|---|---|
| `POST /auth/register` | `{email, password, fullName}` | **202** `{status:"verification_pending"}` — or **201** `{status:"active"}` when verification is disabled | 400 `WEAK_PASSWORD`/`PASSWORD_TOO_LONG`/`VALIDATION_FAILED`; 409 `EMAIL_IN_USE` (only when verification disabled) |
| `POST /auth/verify-email` | `{token}` | 200 | 400 `INVALID_OR_EXPIRED_TOKEN` |
| `POST /auth/resend-verification` | `{email}` | **202** always | 429 |
| `POST /auth/login` | `{email, password}` | 200 `{accessToken, tokenType:"Bearer", expiresAt, user}` + refresh cookie | 401 `INVALID_CREDENTIALS`; 403 `EMAIL_NOT_VERIFIED`/`ACCOUNT_DISABLED`; 429 |
| `POST /auth/refresh` | — (cookie) | 200 same shape as login; cookie **rotated** | 401 `SESSION_INVALID`; 401 `SESSION_REVOKED` (token reuse → all sessions revoked); 403 `CSRF_CHECK_FAILED` |
| `POST /auth/logout` | — (cookie) | 204, cookie cleared | 403 `CSRF_CHECK_FAILED` |
| `POST /auth/forgot-password` | `{email}` | **202** always | 429 |
| `POST /auth/reset-password` | `{token, newPassword}` | 204; **all sessions revoked** | 400 `INVALID_OR_EXPIRED_TOKEN`/`WEAK_PASSWORD` |

Password rules: 12+ characters, at most 72 bytes (BCrypt limit — rejected, never truncated), not a common password, not equal to the email, not trivially repetitive.

Rate limits: login 30/5 min per IP and 10/5 min per email · register 10/h per IP · emails (forgot/resend) 10/h per IP and 3/h per email · token submission 20/10 min per IP · refresh 120/5 min per IP.

## Current user (`/users/me`, bearer)

| Method & path | Body | Success |
|---|---|---|
| `GET /users/me` | — | `{id, email, fullName, status, emailVerifiedAt, lastLoginAt, createdAt}` |
| `PATCH /users/me` | `{fullName}` | updated user |
| `POST /users/me/password` | `{currentPassword, newPassword}` | 204 — every session revoked, re-login required |
| `POST /users/me/sessions/revoke-all` | — | 204 — "sign out everywhere" |

## Organizations (bearer)

| Method & path | Permission | Body | Success |
|---|---|---|---|
| `POST /organizations` | any user | `{name, description?}` | **201** organization; caller becomes OWNER |
| `GET /organizations` | any user | — | organizations the caller actively belongs to, with `myRole` |
| `GET /organizations/{orgId}` | member | — | organization |
| `PATCH /organizations/{orgId}` | `org.manage` | `{name?, description?}` | organization |
| `DELETE /organizations/{orgId}` | `org.manage` | `{confirmSlug}` | 204 (soft delete; members lose access immediately) |
| `GET /organizations/{orgId}/access` | member | — | `{organizationId, role, permissions[]}` — caller's effective permissions |
| `GET /organizations/{orgId}/roles` | member | — | roles with permission codes |

Organization view: `{id, name, slug, description, status, myRole, createdAt}`. Slugs are generated (`acme-corp-3f9a1c`) and immutable.

## Members (bearer)

| Method & path | Permission | Body | Success / errors |
|---|---|---|---|
| `GET /organizations/{orgId}/members` | member | — | page of `{memberId, userId, email, fullName, roleId, role, status, joinedAt}` |
| `POST /organizations/{orgId}/members` | `org.members.manage` | `{email, role}` | **201** `{memberId}`; 404 `USER_NOT_FOUND` (account must exist); 409 `ALREADY_MEMBER`; 403 `OWNER_REQUIRED` |
| `PATCH /organizations/{orgId}/members/{memberId}` | `org.members.manage` | `{role}` | 204; 403 `OWNER_REQUIRED`; 409 `LAST_OWNER` |
| `DELETE /organizations/{orgId}/members/{memberId}` | `org.members.manage` | — | 204; 403 `OWNER_REQUIRED`; 409 `LAST_OWNER` |
| `POST /organizations/{orgId}/leave` | member | — | 204; 409 `LAST_OWNER` |

Roles: `OWNER` (everything), `ADMIN` (everything except `org.manage`), `MEMBER` (read + incident management), `VIEWER` (read-only). Only an OWNER can grant, change or remove the OWNER role; the last OWNER can never be removed or demoted.

## Audit log (bearer)

| Method & path | Permission | Query | Success |
|---|---|---|---|
| `GET /organizations/{orgId}/audit-logs` | `audit.read` | `page`, `size`, `action` (prefix, e.g. `member.`) | page of `{id, actorId, actorEmail, action, resourceType, resourceId, ipAddress, detailsJson, createdAt}` |

## Health

`GET /actuator/health` (public, no details) · `GET /actuator/info`. All other actuator endpoints are not exposed.

## Projects (bearer)

Enums are documented in UPPER_SNAKE case and accepted case-insensitively (`production` = `PRODUCTION`).

| Method & path | Permission | Body / query | Success / errors |
|---|---|---|---|
| `GET /organizations/{orgId}/projects` | `project.read` | `q` (name search), `status` (`ACTIVE`/`ARCHIVED`), `page`, `size` | page of projects |
| `POST /organizations/{orgId}/projects` | `project.manage` | `{name, description?, techStack?, environment?, region?, applicationType?}` | **201**; 409 `PROJECT_NAME_TAKEN` |
| `GET /organizations/{orgId}/projects/{projectId}` | `project.read` | — | project; 404 `PROJECT_NOT_FOUND` |
| `PATCH /organizations/{orgId}/projects/{projectId}` | `project.manage` | any subset of the create fields (blank clears optional text) | project |
| `POST …/projects/{projectId}/archive` · `/restore` | `project.manage` | — | project. Archived projects accept no new services, keys or telemetry |
| `DELETE …/projects/{projectId}` | `project.manage` | `{confirmName}` | 204. Soft delete; its services are deleted and **all its keys revoked** in the same transaction |

Project: `{id, name, description, techStack, environment (DEVELOPMENT|TESTING|STAGING|PRODUCTION|OTHER), region, applicationType, status, serviceCount, connectedServiceCount, createdAt, updatedAt}`.

## Services (bearer)

| Method & path | Permission | Body / query | Success / errors |
|---|---|---|---|
| `GET /organizations/{orgId}/services` | `project.read` | `projectId?`, `q?`, `page`, `size` | page of services (all projects) |
| `GET …/projects/{projectId}/services` | `project.read` | `q?`, `page`, `size` | page of services |
| `POST …/projects/{projectId}/services` | `project.manage` | `{name, description?, technology?}` | **201**; 409 `SERVICE_NAME_TAKEN` / `PROJECT_ARCHIVED` |
| `GET` / `PATCH /organizations/{orgId}/services/{serviceId}` | read / manage | `{name?, description?, technology?}` | service |
| `POST …/services/{serviceId}/archive` · `/restore` | `project.manage` | — | service (archiving disables ingestion) |
| `DELETE …/services/{serviceId}` | `project.manage` | — | 204; service-scoped keys revoked |

Service: `{id, projectId, projectName, name, description, technology (JAVA_SPRING_BOOT|PYTHON|NODEJS|DOTNET|GO|CUSTOM_REST|OPENTELEMETRY|OTHER), status, connection: {integrationType, status, firstTelemetryAt, lastTelemetryAt, lastError}, createdAt}`.

## Integration credentials (bearer, `credential.manage`)

| Method & path | Body | Success / errors |
|---|---|---|
| `GET …/projects/{projectId}/credentials` | — | list of `{id, projectId, serviceId, name, keyPrefix, status, expiresAt, revokedAt, lastUsedAt, rotatedFromId, createdAt}` — never the key |
| `POST …/projects/{projectId}/credentials` | `{name, serviceId?, expiresInDays? (1–365)}` | **201** `{credential, key, notice}` — **the only time the key is ever returned** (`Cache-Control: no-store`); 400 `SERVICE_NOT_IN_PROJECT`; 409 `PROJECT_ARCHIVED` |
| `POST /organizations/{orgId}/credentials/{id}/revoke` | — | credential (status `REVOKED`) |
| `POST /organizations/{orgId}/credentials/{id}/rotate` | `{graceHours? (0–72)}` | `{credential, key, notice}` — new key, same scope; old key revoked now, or valid until the grace deadline; 409 `CREDENTIAL_NOT_ACTIVE` |

Keys look like `po_` + 43 URL-safe characters. Omit `serviceId` for a project-wide key (batches must then name their service). Status is `EXPIRED` once `expiresAt` passes.

## Integration onboarding (bearer)

| Method & path | Permission | Body / query | Success |
|---|---|---|---|
| `GET …/services/{serviceId}/integration` | `project.read` | — | `{serviceId, integrationType, status, firstTelemetryAt, lastTelemetryAt, lastError, receivingNow, usableKeys, nextStep}` — poll this in the "Verify connection" step |
| `PUT …/services/{serviceId}/integration` | `project.manage` | `{type: JAVA_AGENT|REST_API|OTLP}` | status view |
| `POST …/integration/disable` · `/enable` | `project.manage` | — | status view; disabled services reject telemetry (403 `INGESTION_DISABLED`) |
| `GET …/integration/instructions?type=REST_API` | `project.read` | — | `{type, available, unavailableReason, endpoint, steps[{title, description, snippet}]}` |

Connection status: `NOT_CONNECTED` → `WAITING_FOR_DATA` (method chosen) → `CONNECTED` **only after telemetry is actually processed**. `CONNECTION_ERROR` when an authenticated sender's data was rejected before ever connecting; `DISABLED` by user or when a service is archived/deleted. `receivingNow` is false if nothing arrived in the last 15 minutes. JAVA_AGENT/OTLP instructions report `available: false` until the OTLP receiver ships (Phase 7).

## Telemetry ingestion — REST (integration key)

`POST /api/v1/ingest/v1/events` — authenticated with header **`X-PulseOps-Key: po_…`** (or `Authorization: Bearer po_…`); user JWTs are not accepted here. `Content-Type: application/json`, uncompressed, max 1 MiB (`PULSEOPS_INGEST_MAX_BYTES`).

```json
{
  "service": "Order Service",
  "events": [
    {"type": "performance", "name": "GET /orders/{id}", "timestamp": "2026-11-21T10:15:30Z",
     "httpMethod": "GET", "route": "/orders/{id}", "statusCode": 200, "durationMs": 42.5,
     "traceId": "4bf92f3577b34da6a3ce929d0e0e4736", "attributes": {"region": "ap-south-1"}},
    {"type": "error", "name": "PaymentDeclined", "timestamp": "2026-11-21T10:15:31Z",
     "message": "Card issuer declined", "errorType": "PaymentException", "stackTrace": "..."},
    {"type": "metric", "name": "queue.depth", "timestamp": "2026-11-21T10:15:32Z", "value": 17, "unit": "jobs"},
    {"type": "business", "name": "order.placed", "timestamp": "2026-11-21T10:15:33Z", "attributes": {"amount": 49.9}}
  ]
}
```

| Field | Rules |
|---|---|
| `service` | Required for project-wide keys (must already exist — services are never auto-created); optional for service-scoped keys but must match |
| `events` | 1–500 per batch (`PULSEOPS_INGEST_MAX_BATCH`); the whole batch is rejected if any event is invalid |
| `type` | `custom`, `error`, `performance`, `metric`, `business` |
| `name` | 1–200 printable characters |
| `timestamp` | ISO-8601 with offset; not older than 7 days, not more than 5 minutes in the future |
| `attributes` | ≤ 50 keys (≤ 100 chars); values string (≤ 1000 chars), number or boolean |
| performance | `durationMs` 0–3,600,000 required; `statusCode` 100–599; `route` (template) or `path` (raw path, templated automatically: `/orders/123` → `/orders/{id}`). Without route/path it is stored as a non-HTTP measurement |
| metric | `value` (finite number) required, `unit` optional |
| error | `message` (≤ 4000, truncated), `errorType`, `stackTrace` (≤ 16000, truncated); also written as an ERROR log line |
| `traceId` / `spanId` | 32 / 16 hex characters (W3C), optional |

**Responses:** **202** `{batchId, accepted}` once validated and queued (processing is asynchronous) · 400 `VALIDATION_FAILED` with `fieldErrors` like `events[3].timestamp` / `MALFORMED_JSON` / `SERVICE_REQUIRED` · 401 `INVALID_CREDENTIAL` / `CREDENTIAL_REVOKED` / `CREDENTIAL_EXPIRED` · 403 `SERVICE_SCOPE_MISMATCH` / `PROJECT_ARCHIVED` / `SERVICE_ARCHIVED` / `INGESTION_DISABLED` · 404 `UNKNOWN_SERVICE` · 413 `PAYLOAD_TOO_LARGE` · 415 (non-JSON or compressed) · 429 `RATE_LIMITED` (600 batches/min/key) and 503 `INGEST_BUSY` (queue full), both with `Retry-After`.

**Privacy:** attributes whose names indicate secrets (password, token, authorization, cookie, API key, card number, CVV, …) are stored as `[REDACTED]`; bearer tokens, JWTs, PulseOps keys, URL credentials, `password=…` pairs and Luhn-valid card numbers are scrubbed from all text.

## Telemetry ingestion — OpenTelemetry OTLP/HTTP (integration key)

`POST /otlp/v1/traces`, `POST /otlp/v1/logs`, `POST /otlp/v1/metrics` — the standard OTLP/HTTP paths, so exporters only need `OTEL_EXPORTER_OTLP_ENDPOINT=<ingest-base-url>/otlp`. Same key headers and security chain as REST.

| Aspect | Behaviour |
|---|---|
| Encoding | **Binary protobuf only** (`Content-Type: application/x-protobuf`). OTLP/JSON and gRPC are not supported (415 with a hint to set `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`) |
| Compression | `Content-Encoding: gzip` or none. Compressed body ≤ `PULSEOPS_INGEST_MAX_BYTES` (1 MiB default); decompressed ≤ 16 MiB (decompression bombs → 413) |
| Service | Service-scoped key: everything goes to the key's service, whatever `service.name` says. Project-wide key: each resource's `service.name` must name an existing service of the project (case-insensitive) |
| Spans | IDs must be 16/8 bytes and non-zero; start time within −7 days / +5 minutes. Stored in `distributed_traces`; a retried span (same tenant + trace + span ID) is ignored |
| Derived data | SERVER spans with an HTTP method → API request metrics (route from `http.route`, else templated `url.path`); spans with `db.system[.name]` → `database_operations` (statement sanitized to its shape); `exception` span events → ERROR logs linked to the trace |
| Logs | Severity number (or text) → TRACE…FATAL; body redacted, ≤ 8000 chars; trace/span IDs kept for correlation |
| Metrics | Downsampled to one row per (metric, attribute set, minute): count/sum/min/max/last. Gauges and sums keep values; histograms/summaries keep count, sum, min, max. ≤ 2000 series per request (extra points rejected) |
| Attributes | ≤ 64 per item; keys ≤ 128, strings ≤ 1000 (stack traces ≤ 16000); SQL attributes sanitized; secret-like keys `[REDACTED]`; `process.command_line`/`process.command_args` dropped |

**Responses:** **200** with `Export{Trace,Logs,Metrics}ServiceResponse`. Items that cannot be accepted individually — invalid IDs, out-of-window timestamps, or (project-wide keys) a resource naming an unknown/archived/disabled service while other resources are fine — are reported in `partial_success` (`rejected_spans` / `rejected_log_records` / `rejected_data_points` + `error_message`) so exporters log rather than retry them. Whole-request failures return a protobuf **`google.rpc.Status`** body: 400 `MALFORMED_PROTOBUF` / `MALFORMED_GZIP`, 401 key errors, 403 project/scope errors, 404 `UNKNOWN_SERVICE` (when no resource could be attributed), 413, 415, 429 and 503 (both with `Retry-After: 30`).

---

## Monitoring — applications and APIs (JWT; permission `telemetry.read`)

All under `/api/v1/organizations/{orgId}`. Time range: `from`, `to` as ISO-8601 instants (default: the last hour; at most 31 days; `to` ≤ now + 5 min), aligned to whole steps. Series `step` (seconds) is one of 60, 300, 900, 3600, 21600, 86400 — chosen automatically for ≤ 360 points unless given (≤ 1440 points). Latencies in ms, rates as fractions 0–1. **Absent data is null, never 0 ms**; a bucket without requests reports `requests: 0` and null latency. Percentiles are computed from merged latency histograms (ADR-014), never averaged. `errorRate` = 5xx / all requests; 4xx are reported separately as `clientErrors`.

| Endpoint | Returns |
|---|---|
| `GET /monitoring/overview?from&to&projectId` | Org totals (requests, req/s, error rate, p95 from the merged histogram), service counts by health, `attention` (CRITICAL/WARNING or connection ERROR, worst first) and `allServices` |
| `GET /services/{id}/monitoring/summary?from&to` | Requests, req/s, server/client errors, error rate, avg/p50/p95/p99/max, counts per status class, connection status, `health` {status, reasons} |
| `GET /services/{id}/monitoring/timeseries?from&to&step[&method&route]` | Evenly spaced points: requests, req/s, errors, error rate, avg/p50/p95/p99 — for the service, or one endpoint when `method` and `route` (the route template) are given |
| `GET /services/{id}/monitoring/endpoints?from&to&sort&search&limit` | Endpoints (method + route template) with the same statistics and `lastSeen`; `sort` = `requests` (default), `errors`, `error_rate`, `p95`, `p99`, `avg`, `route`; `search` filters routes (contains); `limit` 1–200 (default 50) |
| `GET /services/{id}/monitoring/runtime?from&to&step` | JVM runtime series from OTLP metrics when exported (OpenTelemetry Java agent 2.x names): `cpuUtilization` (0–1), `heapUsedBytes`, `heapLimitBytes`, `threads`, `loadedClasses`; `available=false` and nulls when the service sends none; `metricNames` lists what it does send |

**Health** (ADR-020): `NO_DATA` (no requests), `HEALTHY` (also when fewer than 20 requests — too few to judge), `WARNING` (5xx rate ≥ 1 % or p95 ≥ 500 ms), `CRITICAL` (5xx rate ≥ 5 % or p95 ≥ 1000 ms), with human-readable `reasons`. Errors: 400 `INVALID_TIME_RANGE` / `INVALID_SORT` / `METHOD_REQUIRED` / `INVALID_METHOD`; 404 for unknown services or non-members.

## Monitoring — database (JWT; permission `telemetry.read`)

Under `/api/v1/organizations/{orgId}/services/{serviceId}/monitoring/database`, same `from`/`to`/`step` rules as above. Statements are the shapes stored at ingestion (literals replaced by `?`); parameter values are never collected. Durations are **exact** percentiles over individual calls. `slowMs` (1–600000, default 500) defines a slow call.

| Endpoint | Returns |
|---|---|
| `GET /summary?from&to&slowMs` | Calls, calls/s, errors, error rate, slow calls, total time, avg/p50/p95/p99/max, breakdown by database system × operation type (with share of time), `health` (database thresholds: WARNING at ≥ 1 % errors or p95 ≥ 200 ms, CRITICAL at ≥ 5 % or ≥ 1000 ms) |
| `GET /operations?from&to&sort&search&limit&slowMs` | Query shapes (system, type, statement, table) with calls, errors, slow calls, total time, **share of database time**, avg/p50/p95/p99/max, `lastSeen`, `slowestTraceId`; `sort` = `total_time` (default), `calls`, `avg`, `p95`, `p99`, `max`, `errors`, `error_rate`, `slow`; `search` matches statement or table |
| `GET /timeseries?from&to&step&operation&slowMs` | Calls, calls/s, errors, slow calls, avg/p50/p95/p99 per step, for all calls or one statement |
| `GET /calls?from&to&minDurationMs&errorsOnly&limit` | Individual calls, slowest first, with error message and trace/span IDs (default: calls ≥ 500 ms; with `errorsOnly=true` default 0 ms) |
| `GET /pool?from&to&step` | Connection-pool gauges summed across pools — `used`, `idle`, `max`, `pending`, `peakUsed` — when reported (stable `db.client.connection.*` or older `db.client.connections.*` names, e.g. HikariCP via the Java agent); `available=false` otherwise |

The organization overview also includes `database` totals (calls, errors, error rate, slow calls, p95, health).

## Logs and traces (JWT; permission `telemetry.read`)

Under `/api/v1/organizations/{orgId}`; same `from`/`to` rules (default last hour, ≤ 31 days). Lists are **newest first with keyset pagination**: pass the returned `nextCursor` as `cursor` for the next page (`null` on the last page). Log messages and attributes were redacted at ingestion.

| Endpoint | Returns |
|---|---|
| `GET /logs?from&to&serviceId&minLevel&levels&q&traceId&cursor&limit` | Log entries (service name, time, level, message, logger, trace/span IDs, attributes). `minLevel` (TRACE…FATAL, `WARNING` accepted) or exact `levels` (comma list); `q` = case-insensitive text in the message (≤ 200 chars); `traceId` = 32 hex; `limit` 1–500 (default 100) |
| `GET /logs/histogram?from&to&step&…same filters` | Per step: total and count per level (log volume chart) |
| `GET /traces?from&to&serviceId&minDurationMs&errorsOnly&name&sort&cursor&limit` | Traces with a span in the window (and service): start, duration (first span start → last span end), span and error counts, number of services, root operation and service, `entryName` (first SERVER span, e.g. `POST /api/orders` — clearer than an HTTP-client root named only `POST`), `complete` (root span received). `sort` = `recent` (paginated) or `slowest`; `name` filters the root or entry operation; `limit` 1–200 (default 50) |
| `GET /traces/{traceId}` | All spans (≤ 2000) as a depth-first tree — `depth`, `offsetMs` from trace start, duration, status, attributes, `orphan` when the parent span was not received — plus the trace's logs (≤ 500), services, totals, `complete`, `truncated` |

Errors: 400 `INVALID_TIME_RANGE`, `INVALID_LEVEL`, `INVALID_TRACE_ID`, `INVALID_CURSOR`, `INVALID_SORT`, `INVALID_DURATION`, `QUERY_TOO_LONG`; 404 `TRACE_NOT_FOUND` (also for another organization's trace ID).

## Anomalies (JWT)

Under `/api/v1/organizations/{orgId}/anomalies`. Produced every minute by the detection job (ADR-024) from the ML service's findings (ADR-023); one **active** anomaly per service × metric × scope (`service` or an endpoint like `GET /api/orders/{id}`), merged while it persists and **resolved** automatically after 10 minutes without recurrence.

| Endpoint | Permission | Returns |
|---|---|---|
| `GET ?from&to&status&serviceId&minSeverity&cursor&limit` | `telemetry.read` | Anomalies that were active in the window, newest detection first, keyset-paginated. `status` = `active` (open + linked, default), `open`, `linked`, `resolved`, `dismissed`, `all`; `minSeverity` = low…critical. Each: metric (`request_rate`, `response_latency`, `error_rate`, `db_duration`, `connection_utilization`), scope, type (`spike`/`drop`), severity, observed vs expected, score (robust z), human-readable `reason`, detected/last-seen times, window, occurrences, status, evidence (detector, spread, baseline size, sensitivity) |
| `GET /{id}` | `telemetry.read` | One anomaly |
| `POST /{id}/dismiss` | `incident.manage` | Marks an active anomaly as a false positive (audited `anomaly.dismissed`); 409 `ANOMALY_NOT_ACTIVE` otherwise |
| `GET /detection-status` | `telemetry.read` | Job health (no tenant data): enabled, last run/success, last error (e.g. ML service unavailable), services/series evaluated, series not judged with counts per reason (e.g. `insufficient history`), findings |

## ML service (internal)

`POST {PULSEOPS_ML_URL}/v1/detect` with header `X-PulseOps-ML-Secret` — see `ml-service/README.md`. Not exposed to customers.

