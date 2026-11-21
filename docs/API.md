# PulseOps — API Reference

Base path: `/api/v1`. JSON everywhere. Authenticated endpoints take `Authorization: Bearer <accessToken>`.

## Conventions

- **Errors** — every failure returns:
  ```json
  { "timestamp": "...", "status": 403, "code": "MISSING_PERMISSION",
    "message": "Your role does not grant the 'project.manage' permission",
    "path": "/api/v1/...", "fieldErrors": [ { "field": "email", "message": "must be a well-formed email address" } ] }
  ```
  `code` is stable and machine-readable; `message` is safe to show users. `fieldErrors` appears only on `VALIDATION_FAILED`.
- **Pagination** — list endpoints accept `page` (0-based, default 0) and `size` (default 25, max 100) and return
  `{ "content": [...], "page": 0, "size": 25, "totalElements": 42, "totalPages": 2 }`.
- **Organization scope** — routes under `/organizations/{orgId}/...` require an *active* membership. Non-members receive **404 `ORGANIZATION_NOT_FOUND`** (existence is not revealed). Insufficient role → **403 `MISSING_PERMISSION`**.
- **Rate limits** — exceeded limits return **429 `RATE_LIMITED`**.

---

## Authentication (`/auth`, public)

The refresh token is never in a response body: it is set as cookie `pulseops_refresh` (HttpOnly, Secure, SameSite=Strict, Path=/api/v1/auth). `/auth/refresh` and `/auth/logout` additionally require header **`X-PulseOps-Client: web`** (CSRF defence).

| Method & path | Body | Success | Notable errors |
|---|---|---|---|
| `POST /auth/register` | `{email, password, fullName}` | **202** `{status:"verification_pending"}` — or **201** `{status:"active"}` when verification is disabled | 400 `WEAK_PASSWORD`/`PASSWORD_TOO_LONG`/`VALIDATION_FAILED`; 409 `EMAIL_IN_USE` (only when verification disabled) |
| `POST /auth/verify-email` | `{token}` | 200 | 400 `INVALID_OR_EXPIRED_TOKEN` |
| `POST /auth/resend-verification` | `{email}` | **202** always | 429 |
| `POST /auth/login` | `{email, password}` | 200 `{accessToken, tokenType:"Bearer", expiresAt, user}` + refresh cookie | 401 `INVALID_CREDENTIALS`; 403 `EMAIL_NOT_VERIFIED`/`ACCOUNT_DISABLED`; 429 |
| `POST /auth/refresh` | — (cookie) | 200 same shape as login; cookie **rotated** | 401 `SESSION_INVALID`; 401 `SESSION_REVOKED` (token reuse → all sessions revoked); 403 `CSRF_CHECK_FAILED` |
| `POST /auth/logout` | — (cookie) | 204, cookie cleared | 403 `CSRF_CHECK_FAILED` |
| `POST /auth/forgot-password` | `{email}` | **202** always | 429 |
| `POST /auth/reset-password` | `{token, newPassword}` | 204; **all sessions revoked** | 400 `INVALID_OR_EXPIRED_TOKEN`/`WEAK_PASSWORD` |

Password rules: 12+ characters, at most 72 bytes (BCrypt limit — rejected, never truncated), not a common password, not equal to the email, not trivially repetitive.

Rate limits: login 30/5 min per IP and 10/5 min per email · register 10/h per IP · emails (forgot/resend) 10/h per IP and 3/h per email · token submission 20/10 min per IP · refresh 120/5 min per IP.

## Current user (`/users/me`, bearer)

| Method & path | Body | Success |
|---|---|---|
| `GET /users/me` | — | `{id, email, fullName, status, emailVerifiedAt, lastLoginAt, createdAt}` |
| `PATCH /users/me` | `{fullName}` | updated user |
| `POST /users/me/password` | `{currentPassword, newPassword}` | 204 — every session revoked, re-login required |
| `POST /users/me/sessions/revoke-all` | — | 204 — "sign out everywhere" |

## Organizations (bearer)

| Method & path | Permission | Body | Success |
|---|---|---|---|
| `POST /organizations` | any user | `{name, description?}` | **201** organization; caller becomes OWNER |
| `GET /organizations` | any user | — | organizations the caller actively belongs to, with `myRole` |
| `GET /organizations/{orgId}` | member | — | organization |
| `PATCH /organizations/{orgId}` | `org.manage` | `{name?, description?}` | organization |
| `DELETE /organizations/{orgId}` | `org.manage` | `{confirmSlug}` | 204 (soft delete; members lose access immediately) |
| `GET /organizations/{orgId}/access` | member | — | `{organizationId, role, permissions[]}` — caller's effective permissions |
| `GET /organizations/{orgId}/roles` | member | — | roles with permission codes |

Organization view: `{id, name, slug, description, status, myRole, createdAt}`. Slugs are generated (`acme-corp-3f9a1c`) and immutable.

## Members (bearer)

| Method & path | Permission | Body | Success / errors |
|---|---|---|---|
| `GET /organizations/{orgId}/members` | member | — | page of `{memberId, userId, email, fullName, roleId, role, status, joinedAt}` |
| `POST /organizations/{orgId}/members` | `org.members.manage` | `{email, role}` | **201** `{memberId}`; 404 `USER_NOT_FOUND` (account must exist); 409 `ALREADY_MEMBER`; 403 `OWNER_REQUIRED` |
| `PATCH /organizations/{orgId}/members/{memberId}` | `org.members.manage` | `{role}` | 204; 403 `OWNER_REQUIRED`; 409 `LAST_OWNER` |
| `DELETE /organizations/{orgId}/members/{memberId}` | `org.members.manage` | — | 204; 403 `OWNER_REQUIRED`; 409 `LAST_OWNER` |
| `POST /organizations/{orgId}/leave` | member | — | 204; 409 `LAST_OWNER` |

Roles: `OWNER` (everything), `ADMIN` (everything except `org.manage`), `MEMBER` (read + incident management), `VIEWER` (read-only). Only an OWNER can grant, change or remove the OWNER role; the last OWNER can never be removed or demoted.

## Audit log (bearer)

| Method & path | Permission | Query | Success |
|---|---|---|---|
| `GET /organizations/{orgId}/audit-logs` | `audit.read` | `page`, `size`, `action` (prefix, e.g. `member.`) | page of `{id, actorId, actorEmail, action, resourceType, resourceId, ipAddress, detailsJson, createdAt}` |

## Health

`GET /actuator/health` (public, no details) · `GET /actuator/info`. All other actuator endpoints are not exposed.

---

*Pending:* projects/services (Phase 4), credentials & onboarding (Phase 5), REST ingestion `POST /api/v1/ingest/v1/events` (Phase 6), OTLP `/v1/traces|metrics|logs` (Phase 7).
