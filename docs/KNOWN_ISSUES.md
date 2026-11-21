# PulseOps — Known Issues, Limitations and Risks

| # | Area | Issue | Status |
|---|---|---|---|
| 1 | Build environment | Package registries (Maven Central, npm, PyPI) are blocked in both the cloud build workspace and the PC-side workspace. Java code is **syntax- and cross-reference-checked but not compiled against Spring**, and the JUnit tests are **written but not executed** (ADR-013). SQL is verified against PostgreSQL 16. | OPEN — enable package managers (claude.ai Settings → Capabilities), or run `mvn test` locally |
| 2 | Versions | Dependency versions in `backend/pom.xml` and `frontend/package.json` are pinned from last-known stable lines (ADR-008); re-verify at first online build. | OPEN |
| 3 | Database | Retention cleanup jobs and storage monitoring land in later phases; telemetry tables are unbounded until then. Expired refresh/action tokens have repository delete methods but no scheduled job yet. | PLANNED (Phase 9+/17) |
| 4 | RLS | ~~Backend tenant wiring pending~~ — implemented in Phase 3 (ADR-009). | RESOLVED |
| 5 | Hibernate validation | `ddl-auto=validate` against `timestamptz`/`text`/`uuid` columns is expected to pass but is unverified until the first boot. If a column-type mismatch appears, fix the mapping (not the schema). | VERIFY AT FIRST RUN |
| 6 | Rate limiting | In-memory, per instance. A multi-instance SaaS deployment needs a shared store (PostgreSQL-backed or similar). | OPEN (Phase 17) |
| 7 | Invitations | Members can only be added if they already have an account; invitation emails for new users are not implemented. | PLANNED |
| 8 | Refresh concurrency | Two truly concurrent refreshes with the same token trigger reuse detection and sign the user out (strict, no grace window). The web client must serialize refresh calls (Phase 9 frontend). | BY DESIGN — documented |
| 9 | Access tokens | Cannot be revoked before their 15-minute expiry. Disabled users keep working access tokens until expiry, but lose organization access immediately on removal (membership is checked per request). | BY DESIGN |
| 10 | Frontend | Auth/onboarding screens are not built yet (frontend build blocked by #1). | PLANNED |
| 11 | Ingestion durability | The processing queue is in memory: batches accepted (202) but not yet processed are lost if the backend crashes (ADR-016). Graceful shutdown drains up to 20 s. | BY DESIGN (v1) — durable outbox planned |
| 12 | Ingestion | Compressed bodies (`Content-Encoding: gzip`) are rejected with 415. OpenTelemetry exporters default to no compression for OTLP/HTTP, but this must be supported for OTLP in Phase 7. | PLANNED (Phase 7) |
| 13 | Ingestion | Per-key rate limiting and credential `last_used_at` throttling are per instance (in-memory). | OPEN (Phase 17) |
| 14 | Integrations | Java-agent and OTLP integration instructions are returned with `available=false` until the OTLP receiver exists. | PLANNED (Phase 7) |
| 15 | Metrics | Error rate counts only 5xx as errors (4xx are client errors). Documented; configurable per alert rule later. | BY DESIGN |
| 16 | Verification | `IngestServiceTest`, `TelemetryProcessorTest` and the other Phase 4–6 JUnit tests are written but not executed (KNOWN_ISSUES #1). The JDK-only processing classes (route templates, redaction, histograms, event normalization) **were** compiled and executed here against 81 assertions. | PARTIAL |
