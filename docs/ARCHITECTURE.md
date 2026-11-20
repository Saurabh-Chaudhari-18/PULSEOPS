# PulseOps — Architecture

## 1. Shape

**Modular monolith** (Spring Boot, Java 21) + **separate Python ML service** (FastAPI) + **React SPA**. One PostgreSQL 16 database. No Kafka, no premature microservices (ADR-001/002).

```
Customer Application
  ├─ OTel Java Agent ──► OTLP HTTP ─┐
  ├─ OTel SDKs ───────► OTLP HTTP ──┤
  └─ REST client ────► /api/v1/ingest ─┤
                                       ▼
                   ┌────────────────────────────────────┐
                   │  PulseOps Backend (Spring Boot)    │
                   │  ingest → auth → tenant check →    │
                   │  validate → process → store        │
                   │  ────────────────────────────────  │
                   │  metrics | logs | traces | db-ops  │
                   │  incidents | alerts | RBAC | audit │
                   └───────┬──────────────────┬─────────┘
                           │ JDBC             │ HTTP (internal)
                           ▼                  ▼
                     PostgreSQL 16      ML Service (FastAPI)
                     (RLS enforced)     anomaly detection,
                           ▲            correlation scoring, RCA
                           │
                   React Dashboard ◄── REST + SSE ── Backend
```

## 2. Backend Modules (packages under `com.pulseops`)

`auth`, `user`, `organization`, `rbac`, `project`, `service` (managed services), `integration`, `credential`, `ingest`, `processing`, `metrics`, `logs`, `traces`, `dbmonitor`, `anomaly`, `incident`, `correlation`, `rca`, `recommendation`, `alert`, `analytics`, `audit`, `retention`, plus `common` (shared config, errors, security, tenancy).

**Rules:** each module exposes `api` (controllers + DTOs) and `service`; `repository` and `domain` are module-private. No controller calls another module's repository. No giant classes. DTOs + validation at the boundary; MapStruct-style manual mappers (no runtime magic).

## 3. Data Flow

1. **Ingestion** (`/api/v1/ingest/**`, OTLP paths): authenticate integration credential (hashed lookup) → resolve org/project/service scope → validate payload (size, structure, timestamp) → **202 Accepted** → async processing queue (in-process, bounded).
2. **Processing:** normalize → redact headers/attributes → route-template extraction (low cardinality) → write raw bounded records + upsert 1-minute aggregates.
3. **Detection:** scheduled job ships recent aggregates to ML service → anomalies persisted with evidence.
4. **Correlation:** anomalies grouped into incidents (service/project/time window/trace IDs/error fingerprints); dedupe via incident `dedup_key`.
5. **RCA:** rule engine evaluates evidence patterns → hypotheses with confidence + recommendations.
6. **Alerts:** rule evaluator over aggregates/incidents → notification adapter (email/SMTP first; extensible).
7. **Dashboard:** REST + pagination everywhere; SSE for live incident/anomaly updates.

## 4. Tenancy

JWT carries user id; org membership resolved per request; every repository query filters by `organization_id`; per-transaction `SET LOCAL app.current_org_id` + PostgreSQL **FORCE RLS** as the backstop (ADR-006). App connects as non-owner `pulseops_app` role.

## 5. Telemetry Storage

Aggregates-first (ADR-007): `api_metrics` is (service, route, method, status_class, minute bucket) with count/error/duration stats. Raw `telemetry_events`, `application_logs`, `distributed_traces` (spans), `database_operations` are bounded by retention policies + cleanup jobs; BRIN indexes on time. `TelemetryStore` interface isolates storage for a future ClickHouse backend.

## 6. ML Service

Stateless FastAPI over scikit-learn/pandas/SciPy. Endpoints (internal network only, shared-secret auth):
- `POST /v1/detect` — metric window in, anomalies out (z-score, rolling percentiles, Isolation Forest, baseline comparison; tuned against false positives).
- `POST /v1/similarity` — incident text in, TF-IDF/cosine matches out.
Deterministic core logic lives in `app/core/` (pure functions, fully unit-tested); API layer is thin.

## 7. Deployment

Same codebase, two profiles:
- **dev-lite:** backend + Postgres + ML service, no Docker required, minimal memory.
- **full (self-hosted):** Docker Compose — postgres, backend, ml-service, frontend (nginx), optional prometheus+grafana.
All config via environment variables; `.env.example` documents every variable; no hardcoded secrets or URLs.
