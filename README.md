# PulseOps

**Intelligent Application Observability & Incident Intelligence Platform**

PulseOps lets organizations connect their existing applications (Java/Spring Boot, Python, Node.js, .NET, Go, or anything via REST / OpenTelemetry) and monitor them from one centralized dashboard: API traffic and latency, errors, logs, distributed traces, database operations — with ML-based anomaly detection, automated incident correlation, and evidence-based root cause hypotheses.

## Repository Layout (monorepo)

| Path | Contents |
|---|---|
| `backend/` | Spring Boot modular-monolith backend (Java 21) |
| `frontend/` | React + TypeScript + Vite + Material UI dashboard |
| `ml-service/` | Python FastAPI service: anomaly detection, correlation, RCA support |
| `telemetry/` | Integration assets: OTel agent configs, collector configs |
| `database/` | Flyway SQL migrations and database documentation assets |
| `deployment/` | Docker Compose, environment templates, self-hosted deployment |
| `docs/` | PRD, architecture, security, development and status docs |
| `scripts/` | Development and operational helper scripts |
| `examples/` | Sample instrumented Spring Boot application |

## Documentation

- [docs/PRD.md](docs/PRD.md) — product requirements
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — system architecture
- [docs/DATABASE.md](docs/DATABASE.md) — schema and data design
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) — local development guide
- [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md) — phase-by-phase progress
- [docs/DECISIONS.md](docs/DECISIONS.md) — architecture decision records
- [docs/KNOWN_ISSUES.md](docs/KNOWN_ISSUES.md) — bugs, limitations, risks

## Deployment Models

1. **Cloud SaaS** — multi-tenant, organization-isolated.
2. **Self-hosted** — Docker Compose, environment-based configuration (see `deployment/`).

## Status

Early implementation. See [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md) for what is complete, in progress, and pending. Nothing in this repository is claimed production-ready until the Definition of Done in the PRD passes.
