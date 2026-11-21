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

## Tests

| Component | Command |
|---|---|
| Backend | `cd backend && mvn test` |
| ML service | `cd ml-service && pytest` |
| Frontend | `cd frontend && npm test` |

Run test suites one component at a time on low-memory machines; avoid parallel builds.

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
| `PULSEOPS_INGEST_MAX_BYTES` / `PULSEOPS_INGEST_MAX_BATCH` | `1048576` / `500` | Ingestion limits (Phase 6) |
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

Modern browsers and recent curl versions treat `http://localhost` as a secure context and keep Secure cookies. If your client drops the refresh cookie over plain HTTP, set `PULSEOPS_COOKIE_SECURE=false` for local development only.

## Repository conventions

- Module boundaries: see docs/ARCHITECTURE.md §2. No cross-module repository access.
- Schema changes: new Flyway migration only — never edit an applied migration, never use `ddl-auto=update`.
- Every phase updates docs/IMPLEMENTATION_STATUS.md; significant choices get an ADR in docs/DECISIONS.md.
