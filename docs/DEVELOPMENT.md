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

Every variable is documented in `deployment/.env.example`. No secrets are ever committed; no variable is undocumented.

## Repository conventions

- Module boundaries: see docs/ARCHITECTURE.md §2. No cross-module repository access.
- Schema changes: new Flyway migration only — never edit an applied migration, never use `ddl-auto=update`.
- Every phase updates docs/IMPLEMENTATION_STATUS.md; significant choices get an ADR in docs/DECISIONS.md.
