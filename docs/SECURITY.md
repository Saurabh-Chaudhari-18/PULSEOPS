# PulseOps — Security

Security is a first-class requirement. This document describes the controls **as implemented**, and lists what is still pending. Report vulnerabilities privately to the maintainers; do not open public issues for them.

## 1. Authentication

| Control | Implementation |
|---|---|
| Password hashing | BCrypt, cost 12 (ADR-004). Passwords > 72 bytes are **rejected**, never silently truncated. |
| Password policy | ≥ 12 chars; block-list of common passwords; ≠ email; not trivially repetitive (NIST 800-63B style, no composition rules). |
| Access tokens | JWT, HS256, 15-minute TTL, issuer-validated. Secret from `PULSEOPS_JWT_SECRET` (base64, ≥ 32 bytes) — **the app refuses to start without it**; there is no default key. Tokens carry identity only. |
| Refresh tokens | Opaque 256-bit random values; only **SHA-256 hashes** stored; **rotated on every use**; presenting an already-rotated token is treated as theft → **every session of that user is revoked** and the event is audited. |
| Refresh-token transport | HttpOnly + Secure + SameSite=Strict cookie, path-scoped to `/api/v1/auth`; never in response bodies (unreadable by page scripts/XSS). |
| CSRF | Bearer-token API calls are not CSRF-able (browsers never attach the header). Cookie endpoints (refresh, logout) also require `X-PulseOps-Client: web`, which cross-site requests cannot set without passing CORS preflight. |
| Logout / revocation | Logout revokes the presented refresh token; "sign out everywhere", password change and password reset revoke all refresh tokens. Issued access tokens expire within 15 minutes. |
| Email verification | Required by default. One-time, hashed, expiring tokens; issuing a new one invalidates older ones. Dev profile logs links instead of emailing (`pulseops.mail.dev-log-only`, bound to the `dev` profile only). Production with verification on and no SMTP **fails at startup** rather than running in a state where nobody can verify. |
| Password reset | One-time, hashed, 30-minute tokens; reset revokes all sessions. |
| Account enumeration | Registration (with verification on), forgot-password and resend-verification return identical responses for known and unknown emails; existing-account owners get a notice email instead. Login runs a BCrypt comparison even for unknown emails. Emails are sent after commit and asynchronously, so response timing does not depend on whether mail was sent. Account status is only disclosed after the correct password is given. |
| Brute force | In-memory rate limits per IP and per email on login, registration, email sending, token submission and refresh (see API.md). Trade-off: per-email login limits let an attacker temporarily (5 min) block sign-in for a known email. |

## 2. Authorization and tenant isolation

- **Membership is resolved per request** from the database, not embedded in tokens — role changes and removals take effect on the next request.
- `@RequiresPermission` on every privileged route, enforced before the controller runs. A permission annotation on a route without an organization scope fails closed (500), never open.
- Non-members get **404**, not 403, so organization IDs cannot be probed.
- Escalation rules: only an OWNER can grant/change/remove OWNER; the last OWNER cannot be removed or demoted (enforced under row locks against concurrent requests).
- **Defense in depth — PostgreSQL Row-Level Security** (ADR-006, ADR-009): `FORCE ROW LEVEL SECURITY` on all 22 tenant tables. Every pooled connection checkout sets `app.current_org_id` and `app.current_user_id` (empty when absent → policies match nothing → **fail closed**). A missing `WHERE organization_id = ?` in application code returns no rows instead of another tenant's data; a cross-tenant write is rejected by the database.
- The application **must** connect as a non-superuser role without `BYPASSRLS` (`pulseops_app`); superusers bypass RLS.

## 3. Audit logging

Append-only `audit_logs`: registration, email verification, logins and failed logins, refresh-token reuse, password changes/resets, session revocations, organization create/update/delete, member add/role change/remove/leave. Entries record actor, action, resource, client IP and user agent. The organization of an entry is always taken from the bound tenant context (RLS prevents writing into another tenant or forging platform-level rows from a tenant context). Audit entries share the transaction of the audited change.

## 4. Transport, headers and errors

- Security headers: `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, no-cache on authenticated responses (Spring Security defaults).
- CORS: explicit allow-list (`PULSEOPS_CORS_ORIGINS`), credentials allowed only for those origins.
- Errors never include stack traces, SQL, or constraint names; unexpected exceptions are logged server-side and returned as generic `INTERNAL_ERROR`.
- Client IPs come from the socket unless `PULSEOPS_FORWARD_HEADERS` is set — only enable it behind a trusted reverse proxy, otherwise clients could spoof `X-Forwarded-For` to dodge rate limits.
- HTTPS: terminate TLS at a reverse proxy (documented in Phase 18); keep `PULSEOPS_COOKIE_SECURE=true`.
- All actuator endpoints except `health` (no details) and `info` are unexposed; everything not explicitly routed is denied.

## 5. Secrets

- No secrets in the repository; all configuration via environment variables (see DEVELOPMENT.md).
- One-time tokens and refresh tokens: hashed at rest. Integration credentials (Phase 5) will follow the same rule and be shown once.
- Email bodies containing one-time links are never logged on failure; the dev mail mode logs them by design and is restricted to the `dev` profile.

## 6. Known gaps (tracked in KNOWN_ISSUES.md)

- Rate limits are per instance (in-memory); multi-instance SaaS needs a shared store.
- No email invitations for people without an account yet (members must register first).
- Access tokens cannot be revoked before their 15-minute expiry (by design; short TTL).
- Pending phases: telemetry redaction & payload limits (6–7), integration credential security (5), SSRF protection for outbound integrations (15), dependency vulnerability scanning in CI (17).
