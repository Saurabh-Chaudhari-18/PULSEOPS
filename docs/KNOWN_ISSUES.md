# PulseOps — Known Issues, Limitations and Risks

| # | Area | Issue | Status |
|---|---|---|---|
| 1 | Build environment | Package registries (Maven Central, npm, PyPI) are blocked in both the cloud build workspace and the PC-side workspace. Java code is **syntax- and cross-reference-checked but not compiled against Spring**, and the JUnit tests are **written but not executed** (ADR-013). SQL is verified against PostgreSQL 16. | MITIGATED — tests now run on the developer PC (IntelliJ). First run: Phase 3 compiles against real Spring; 65/67 passed, 2 failures fixed (see IMPLEMENTATION_STATUS). Enabling package managers in claude.ai Settings → Capabilities would let builds run in the cloud too |
| 2 | Versions | Dependency versions in `backend/pom.xml` and `frontend/package.json` are pinned from last-known stable lines (ADR-008); re-verify at first online build. | OPEN |
| 3 | Database | Retention cleanup jobs and storage monitoring land in later phases; telemetry tables are unbounded until then. Expired refresh/action tokens have repository delete methods but no scheduled job yet. | PLANNED (Phase 9+/17) |
| 4 | RLS | ~~Backend tenant wiring pending~~ — implemented in Phase 3 (ADR-009). | RESOLVED |
| 5 | Hibernate validation | ~~Unverified until first boot~~ — verified: the application boots against PostgreSQL 16 with `ddl-auto=validate` (FullJourneyIntegrationTest). | RESOLVED |
| 6 | Rate limiting | In-memory, per instance. A multi-instance SaaS deployment needs a shared store (PostgreSQL-backed or similar). | OPEN (Phase 17) |
| 7 | Invitations | Members can only be added if they already have an account; invitation emails for new users are not implemented. | PLANNED |
| 8 | Refresh concurrency | Two truly concurrent refreshes with the same token trigger reuse detection and sign the user out (strict, no grace window). The web client must serialize refresh calls (Phase 9 frontend). | BY DESIGN — documented |
| 9 | Access tokens | Cannot be revoked before their 15-minute expiry. Disabled users keep working access tokens until expiry, but lose organization access immediately on removal (membership is checked per request). | BY DESIGN |
| 10 | Frontend | Auth/onboarding screens are not built yet (frontend build blocked by #1). | PLANNED |
| 11 | Ingestion durability | The processing queue is in memory: batches accepted (202) but not yet processed are lost if the backend crashes (ADR-016). Graceful shutdown drains up to 20 s. | BY DESIGN (v1) — durable outbox planned |
| 12 | Ingestion | ~~gzip unsupported~~ — the OTLP receiver accepts `Content-Encoding: gzip` with a 16 MiB decompression cap. The REST endpoint still accepts only uncompressed JSON (415 otherwise). | RESOLVED for OTLP; REST unchanged |
| 13 | Ingestion | Per-key rate limiting and credential `last_used_at` throttling are per instance (in-memory). | OPEN (Phase 17) |
| 14 | Integrations | ~~Java-agent/OTLP guides unavailable~~ — enabled with the Phase 7 receiver; guides now set `http/protobuf` and gzip. | RESOLVED |
| 15 | Metrics | Error rate counts only 5xx as errors (4xx are client errors). Documented; configurable per alert rule later. | BY DESIGN |
| 16 | Verification | ~~Phase 4–6 tests not executed~~ — all executed and passing on the developer PC, including the DB-backed end-to-end test. | RESOLVED |
| 17 | OTLP | Only OTLP/HTTP with binary protobuf is accepted. OTLP/gRPC and OTLP/JSON exporters get 415 (ADR-019). | BY DESIGN (v1) |
| 18 | OTLP metrics | Metrics are stored as one-minute aggregates (count/sum/min/max/last per series); histogram bucket boundaries, exemplars and per-instance identity (`service.instance.id`) are not kept, so two instances of a service reporting the same gauge are averaged together. Cumulative sums are stored as reported (no delta conversion). | BY DESIGN (v1) — revisit with metric dashboards (Phase 9) |
| 19 | OTLP retries | Exporter retries deduplicate spans (ADR-018) but derived request metrics, DB operations and logs from a retried export are stored again. A request split across several services can be half-queued if the queue fills between batches (503 → retry). | ACCEPTED — see ADR-018 |
| 20 | OTLP size | The compressed body limit is shared with REST (1 MiB default). Very large uncompressed agent exports may hit 413; the guides enable gzip, and `PULSEOPS_INGEST_MAX_BYTES` can be raised. | DOCUMENTED |
| 21 | OTLP verification | ~~Protobuf code not yet compiled~~ — compiled and all tests (incl. the OTLP end-to-end journey) pass on the developer PC. | RESOLVED |
| 22 | JDK 25 warning | `protobuf-java` 4.28.3 calls a terminally deprecated `sun.misc.Unsafe` method and the JVM prints a warning at first use. Harmless today; upgrade protobuf-java when a release without it is pinned (re-verify generated-code compatibility). | OPEN (low) |
