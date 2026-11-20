# PulseOps — Known Issues, Limitations and Risks

| # | Area | Issue | Status |
|---|---|---|---|
| 1 | Build environment | Package registries (Maven Central, npm, PyPI) blocked in the cloud build workspace → backend compilation, frontend `npm install`, and `pip install fastapi/httpx` cannot run yet. All Java/TS code is therefore **written but not compiled**; Python core logic **is** tested (sklearn/pandas/pytest available). | OPEN — waiting on network capability (claude.ai Settings → Capabilities → allow package managers; may require a new session) |
| 2 | Versions | Dependency versions in `backend/pom.xml` and `frontend/package.json` are pinned from last-known stable lines (ADR-008) and must be re-verified at first online build. | OPEN |
| 3 | Database | Retention/cleanup jobs and storage monitoring are planned (Phase 9+/17); until then telemetry tables grow unbounded in dev. | PLANNED |
| 4 | RLS | RLS policies are in the schema (Phase 2), but the backend's per-transaction `SET app.current_org_id` wiring lands in Phase 3; until then RLS is enforced only when connecting as `pulseops_app`. | PLANNED |
