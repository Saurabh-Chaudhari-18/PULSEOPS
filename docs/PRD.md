# PulseOps — Product Requirements Document

## 1. Product

PulseOps is an intelligent application observability, performance monitoring and incident intelligence platform. Organizations connect existing applications and monitor them from one centralized dashboard.

**Vision:** help engineering teams understand what is happening inside their applications, detect problems early, and investigate incidents faster.

## 2. Supported Integrations

| Method | v1 scope |
|---|---|
| OpenTelemetry Java Agent (Spring Boot) | ✅ Phase 8 |
| REST ingestion API (any language) | ✅ Phase 6 |
| OTLP HTTP/protobuf (metrics, logs, traces) | ✅ Phase 7 |
| OTel Collector | Extensible path, post-v1 |
| Python/Node/.NET/Go via OTel SDKs → OTLP | Works through Phase 7 endpoint |

## 3. Data Collected

API traffic, response times, HTTP status codes, application errors, logs, distributed traces, database operations and performance, infrastructure metrics when available, health indicators.

**Never collected by default:** passwords, auth tokens, Authorization headers, payment card data, DB passwords, sensitive request bodies. Header redaction + attribute filtering + payload limits are mandatory.

## 4. Intelligence

- **Anomaly detection** (no paid AI APIs): moving averages, rolling std-dev, z-score, rolling percentiles, Isolation Forest, baseline comparison — over request rate, latency, error rate, DB duration, connection utilization.
- **Incident correlation:** group related anomalies by service/project/time window/trace IDs/error patterns; dedupe ongoing events.
- **Root cause analysis:** rule-based, evidence-driven hypotheses with confidence scores, supporting observations, related logs/traces, troubleshooting steps. Never claim a confirmed root cause without evidence.
- **Historical matching:** error fingerprints, TF-IDF + cosine similarity. (pgvector/local LLMs: optional, future.)

## 5. Core Journeys

1. **Onboarding:** register → verify email → create organization → create project → add services → integration wizard (select tech → generate credential shown once → install instructions → verify real telemetry reception) → dashboard. Connection status: Not Connected / Waiting for Data / Connected / Connection Error / Disabled — *connected only after verified telemetry*.
2. **Monitoring:** overview ("what needs my attention today?"), application detail, API endpoint stats (count, avg, p50/p95/p99, error rate, trends; low-cardinality route templates), DB operations, searchable logs, trace timelines.
3. **Incidents:** lifecycle Open → Investigating → Acknowledged → Resolved → Closed; severity Critical/High/Medium/Low; assignment, comments, timeline, related evidence, RCA hypotheses, recommendations, auditable transitions.
4. **Alerts:** threshold rules with duration windows, severity, enable/disable, history, delivery status; extensible notification adapter (email via SMTP when configured; no mandatory paid providers).

## 6. Non-Functional Requirements

- **Multi-tenant:** every tenant-owned record tied to an organization; app-level authorization + PostgreSQL RLS; cross-tenant isolation tests must pass.
- **Security:** see SECURITY.md — JWT + rotating refresh tokens, BCrypt, hashed one-time-display API credentials with rotation/revocation/expiry/last-used, RBAC, rate limiting, audit logging, OWASP basics (SQLi/XSS/CSRF/SSRF), HTTPS support.
- **Deployment:** same codebase as cloud SaaS and self-hosted (Docker Compose); environment-based config; no hardcoded URLs/secrets.
- **Hardware honesty:** runs for development on an 8 GB machine; Docker optional locally; lightweight dev profile + full deployment profile; no Kafka in v1.
- **Zero-cost:** free/open-source stack; no paid AI APIs, DB subscriptions, hosting or monitoring services required.

## 7. Technology Stack (fixed)

Frontend: React, TypeScript, Vite, Material UI, Recharts, React Router, TanStack Query.
Backend: Java 21, Spring Boot, Spring Security, Spring Data JPA/Hibernate, PostgreSQL, REST, SSE/WebSocket where appropriate.
ML: Python, FastAPI, scikit-learn, pandas, NumPy, SciPy, joblib.
Observability: OpenTelemetry (+ Java Agent), OTLP HTTP/protobuf, Prometheus, Grafana.
Testing: JUnit 5, Mockito, pytest, Vitest, React Testing Library, Postman, JMeter.
Infra: Git, GitHub, Docker, Docker Compose, GitHub Actions.

## 8. Definition of Done (v1.0)

All 24 criteria from the product brief, including: full onboarding works end-to-end without admin help; Java + REST + OTLP ingestion verified with a real sample app; monitoring/logs/traces/anomalies/incidents/RCA/alerts functional; multi-tenant isolation + security tests pass; performance tests documented with **measured** (never invented) numbers; self-hosted deployment installs independently; documentation complete. Starting successfully ≠ production-ready.

## 9. Phases

Implementation proceeds through Phases 0–19 in order (see IMPLEMENTATION_STATUS.md). A phase is complete only when its acceptance criteria pass.
