# PulseOps — Development Guide

Optimized for an 8 GB RAM machine. Docker is **optional** for local development (dev-lite mode runs everything natively). Never run every component simultaneously unless you need to.

## Prerequisites

- Java 21 (JDK), Maven 3.9+
- Node.js 20+ / npm 10+
- Python 3.11+
- PostgreSQL 15/16 (native install, or Docker if you prefer)

## dev-lite: run components individually

### 1. Database

```sql
-- as postgres superuser, once:
CREATE ROLE pulseops_app LOGIN PASSWORD '<choose-a-password>';
CREATE DATABASE pulseops OWNER postgres;
GRANT CONNECT ON DATABASE pulseops TO pulseops_app;
```

Flyway runs automatically on backend start and applies `backend/src/main/resources/db/migration`. The app connects as `pulseops_app` (NOT the table owner) so Row-Level Security applies — see docs/DATABASE.md.

### 2. Backend

```bash
cd backend
export PULSEOPS_DB_PASSWORD=...       # pulseops_app password
export PULSEOPS_JWT_SECRET=$(openssl rand -base64 48)
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Dev profile notes:
- **Email verification dev mode** (`pulseops.mail.dev-log-only=true`): verification/reset links are written to the application log instead of being emailed, so you can complete the registration journey without SMTP. Production (`prod` profile) requires real SMTP settings; the dev switch does not weaken production because it is bound to the `dev` profile only.

### 3. ML service

```bash
cd ml-service
python -m venv .venv && . .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements-dev.txt
uvicorn app.main:app --port 8500
```

### 4. Frontend

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173, proxies /api → :8080
```

The dev server proxies `/api` to the backend, so the browser sees one origin (no CORS; the refresh cookie works). If the backend is not on 8080, set `PULSEOPS_BACKEND_URL` first — PowerShell: `$env:PULSEOPS_BACKEND_URL = "http://localhost:18080"`. Links in verification/reset emails use `PULSEOPS_PUBLIC_URL` (default `http://localhost:5173`).

Other scripts: `npm test` (Vitest), `npm run typecheck` (TypeScript), `npm run build` (type-check + production bundle in `dist/`). Commit `package-lock.json` after the first install so later installs are reproducible.

## Tests

| Component | Command |
|---|---|
| Backend | `cd backend && mvn test` |
| ML service | `cd ml-service && pytest` |
| Frontend | `cd frontend && npm test` |

Run test suites one component at a time on low-memory machines; avoid parallel builds.

### Database-backed backend tests (real PostgreSQL)

Two test classes run only when `PULSEOPS_TEST_DB_URL` is set:

- `TenantIsolationIntegrationTest`: RLS through the real `TenantAwareDataSource` on a one-connection pool.
- `FullJourneyIntegrationTest`: boots the **whole application** (Flyway, Hibernate validation, both security chains, async processor) and walks register → login → org → project → service → key → telemetry → CONNECTED, plus cross-tenant 404, revoked key, refresh-token reuse, and CSRF checks.

Use a **disposable** database owned by a non-superuser role. With Docker (≈ 40 MB RAM idle):

```bash
docker run -d --name pulseops-pg -e POSTGRES_PASSWORD=postgres -p 5433:5432 postgres:16-alpine
docker exec pulseops-pg psql -U postgres -c "CREATE ROLE pulseops_app LOGIN PASSWORD 'dev_only_password'" -c "CREATE DATABASE pulseops_it OWNER pulseops_app"
```

Then set, for the test run (IntelliJ: Run → Edit Configurations → your JUnit configuration → Environment variables):

```
PULSEOPS_TEST_DB_URL=jdbc:postgresql://localhost:5433/pulseops_it
PULSEOPS_TEST_DB_USER=pulseops_app
PULSEOPS_TEST_DB_PASSWORD=dev_only_password
```

Tests use random names, so they can be re-run against the same database. Reset with `docker rm -f pulseops-pg`.

## Environment variables

No secrets are ever committed; no variable is undocumented. (`deployment/.env.example` arrives with Phase 18.)

| Variable | Default | Purpose |
|---|---|---|
| `PULSEOPS_DB_URL` | `jdbc:postgresql://localhost:5432/pulseops` | JDBC URL |
| `PULSEOPS_DB_USER` | `pulseops_app` | DB role — must NOT be superuser/BYPASSRLS |
| `PULSEOPS_DB_PASSWORD` | *(empty)* | DB password |
| `PULSEOPS_DB_POOL_SIZE` | `10` | Hikari max pool size |
| `PULSEOPS_JWT_SECRET` | **required** | base64, ≥ 32 bytes decoded; app refuses to start without it |
| `PULSEOPS_JWT_ACCESS_TTL` | `15m` | Access-token lifetime |
| `PULSEOPS_JWT_REFRESH_TTL` | `14d` | Refresh-token lifetime |
| `PULSEOPS_COOKIE_SECURE` | `true` | Secure flag on the refresh cookie |
| `PULSEOPS_CORS_ORIGINS` | `http://localhost:5173` | Comma-separated allowed web origins |
| `PULSEOPS_PUBLIC_URL` | `http://localhost:5173` | Web UI base URL used in email links |
| `PULSEOPS_REQUIRE_EMAIL_VERIFICATION` | `true` | Require verified email before login |
| `PULSEOPS_VERIFICATION_TOKEN_TTL` | `24h` | Verification link lifetime |
| `PULSEOPS_RESET_TOKEN_TTL` | `30m` | Password-reset link lifetime |
| `PULSEOPS_MAIL_FROM` | `no-reply@pulseops.local` | Sender address |
| `SPRING_MAIL_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` | *(unset)* | SMTP server. Required when verification is on, unless running the `dev` profile |
| `PULSEOPS_FORWARD_HEADERS` | `none` | `native`/`framework` ONLY behind a trusted reverse proxy |
| `PULSEOPS_PORT` | `8080` | HTTP port |
| `PULSEOPS_ML_URL` / `PULSEOPS_ML_SECRET` | `http://localhost:8500` / *(empty)* | ML service (Phase 12) |
| `PULSEOPS_INGEST_PUBLIC_URL` | `http://localhost:8080` | URL customer apps use to reach ingestion (shown in setup instructions) |
| `PULSEOPS_INGEST_MAX_BYTES` / `PULSEOPS_INGEST_MAX_BATCH` | `1048576` / `500` | Request body limit / events per batch |
| `PULSEOPS_INGEST_QUEUE_CAPACITY` / `PULSEOPS_INGEST_WORKERS` | `200` / `2` | Buffered batches before 503 / processing threads |
| `PULSEOPS_INGEST_RATE_PER_KEY` | `600` | Batches per minute per integration key |
| `PULSEOPS_TEST_DB_URL` / `_USER` / `_PASSWORD` | *(unset)* | Enables the real-PostgreSQL tenant isolation integration test |

## Trying the auth flow locally (dev profile)

```bash
# 1. Register — the verification link appears in the backend log ("DEV MAIL")
curl -s -X POST localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"correct horse battery staple","fullName":"You"}'
# 2. Verify with the token from the logged link
curl -s -X POST localhost:8080/api/v1/auth/verify-email -H 'Content-Type: application/json' -d '{"token":"<token>"}'
# 3. Log in (stores the refresh cookie in cookies.txt)
curl -s -c cookies.txt -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"correct horse battery staple"}'
# 4. Create an organization with the returned accessToken
curl -s -X POST localhost:8080/api/v1/organizations -H "Authorization: Bearer <accessToken>" \
  -H 'Content-Type: application/json' -d '{"name":"Acme"}'
# 5. Refresh (rotates the cookie)
curl -s -b cookies.txt -c cookies.txt -X POST localhost:8080/api/v1/auth/refresh -H 'X-PulseOps-Client: web'
```

### Sending telemetry (after creating a project, a service named "Order Service" and a key)

```bash
curl -s -X POST localhost:8080/api/v1/ingest/v1/events -H 'Content-Type: application/json' \
  -H "X-PulseOps-Key: $PULSEOPS_INGEST_KEY" \
  -d '{"service":"Order Service","events":[{"type":"performance","name":"GET /orders/{id}",
       "timestamp":"'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'","httpMethod":"GET","path":"/orders/42",
       "statusCode":200,"durationMs":37}]}'
# → 202 {"batchId":"…","accepted":1}; the service's integration status turns CONNECTED.
```

Modern browsers and recent curl versions treat `http://localhost` as a secure context and keep Secure cookies. If your client drops the refresh cookie over plain HTTP, set `PULSEOPS_COOKIE_SECURE=false` for local development only.

## Repository conventions

- Module boundaries: see docs/ARCHITECTURE.md §2. No cross-module repository access.
- Schema changes: new Flyway migration only — never edit an applied migration, never use `ddl-auto=update`.
- Every phase updates docs/IMPLEMENTATION_STATUS.md; significant choices get an ADR in docs/DECISIONS.md.

## Running the backend with real telemetry

`examples/order-service/README.md` walks through starting the backend against a local
database, creating a demo service and key (`scripts/dev-bootstrap.ps1`), and running the
sample service with the OpenTelemetry Java agent so PulseOps receives real traces, logs
and metrics.

## Running the ML service and anomaly detection

See `ml-service/README.md`. In short: create a virtual environment in `ml-service`, `pip install -r requirements-dev.txt`, run `python -m pytest`, then start it with `uvicorn --factory app.main:create_app --host 127.0.0.1 --port 8500` and the same `PULSEOPS_ML_SECRET` (≥ 32 characters) as the backend. The backend's job calls `PULSEOPS_ML_URL` (default `http://localhost:8500`) every minute; `scripts/dev-anomalies.ps1` shows its status and the anomalies found.

