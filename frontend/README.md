# PulseOps web app

React 19 + TypeScript + Vite + MUI 7 + TanStack Query 5 + React Router 7. Design and session decisions: `docs/DECISIONS.md` ADR-026.

```bash
npm install
npm run dev          # http://localhost:5173 — proxies /api to PULSEOPS_BACKEND_URL (default http://localhost:8080)
npm test             # unit tests (Vitest)
npm run typecheck    # TypeScript
npm run build        # type-check + production bundle in dist/
```

PowerShell, backend on another port: `$env:PULSEOPS_BACKEND_URL = "http://localhost:18080"; npm run dev`

Layout of `src/`: `api/` (HTTP client, types, endpoint functions), `auth/` (session context, route guards), `layout/` (shell, organization context), `components/` (shared UI), `pages/` (screens by area), `lib/` (formatting, storage, form errors), `theme.ts` (design tokens).
