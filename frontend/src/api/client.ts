/**
 * HTTP client for the PulseOps API.
 *
 * - The access token lives only in memory (never localStorage), so a script injected into the
 *   page cannot read a long-lived credential from storage.
 * - The refresh token is an HttpOnly cookie the browser sends to /api/v1/auth only; refresh and
 *   logout carry the X-PulseOps-Client header the backend requires as a CSRF defence.
 * - A 401 triggers one shared refresh, then the request is retried once.
 */

export interface FieldError {
  field: string;
  message: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: FieldError[];

  constructor(status: number, code: string, message: string, fieldErrors: FieldError[] = []) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
  }

  fieldMessage(field: string): string | undefined {
    return this.fieldErrors.find((f) => f.field === field)?.message;
  }
}

export interface Session {
  accessToken: string;
  expiresAt: string;
  user: SessionUser;
}

export interface SessionUser {
  id: string;
  email: string;
  fullName: string;
  status: string;
}

type Fetch = typeof fetch;

let accessToken: string | null = null;
let refreshing: Promise<Session | null> | null = null;
let onSessionChange: (session: Session | null) => void = () => {};
let fetchImpl: Fetch = (...args) => globalThis.fetch(...args);

export const BASE = '/api/v1';
const CLIENT_HEADER = { 'X-PulseOps-Client': 'web' };

/** For tests. */
export function configureClient(options: { fetch?: Fetch }) {
  if (options.fetch) fetchImpl = options.fetch;
}

export function setSession(session: Session | null) {
  accessToken = session?.accessToken ?? null;
}

export function hasAccessToken(): boolean {
  return accessToken !== null;
}

/** Called whenever a refresh produces a new session or the session ends. */
export function onSessionChanged(listener: (session: Session | null) => void) {
  onSessionChange = listener;
}

async function toError(response: Response): Promise<ApiError> {
  let body: { code?: string; message?: string; fieldErrors?: FieldError[] } = {};
  try {
    body = await response.json();
  } catch {
    // Not JSON (e.g. a proxy error page): fall through to a generic message.
  }
  const fallback =
    response.status >= 500 || response.status === 0
      ? 'The PulseOps server had a problem handling this request. Try again in a moment.'
      : `Request failed (${response.status}).`;
  return new ApiError(
    response.status,
    body.code ?? `HTTP_${response.status}`,
    body.message ?? fallback,
    body.fieldErrors ?? [],
  );
}

function networkError(): ApiError {
  return new ApiError(0, 'NETWORK', 'Cannot reach the PulseOps server. Check that the backend is running.');
}

async function send(path: string, init: RequestInit): Promise<Response> {
  try {
    return await fetchImpl(`${BASE}${path}`, { credentials: 'same-origin', ...init });
  } catch {
    throw networkError();
  }
}

/**
 * Refresh tokens rotate and a reused one revokes every session, so two tabs must never refresh
 * with the same cookie at once. The Web Locks API serialises refreshes across tabs; the second
 * tab then sends the cookie the first one just received. Browsers without it run unguarded.
 */
function acrossTabs<T>(action: () => Promise<T>): Promise<T> {
  const locks = (globalThis.navigator as Navigator | undefined)?.locks;
  return locks ? locks.request('pulseops-session-refresh', action) : action();
}

/**
 * Exchanges the refresh cookie for a new session. Concurrent callers share one request.
 * Resolves to null when the session is over; rejects (code NETWORK) when the server is
 * unreachable, so a network blip does not sign the user out.
 */
export function refreshSession(): Promise<Session | null> {
  if (!refreshing) {
    refreshing = (async () => {
      try {
        const response = await acrossTabs(() => send('/auth/refresh', { method: 'POST', headers: CLIENT_HEADER }));
        if (!response.ok) {
          setSession(null);
          return null;
        }
        const session = (await response.json()) as Session;
        setSession(session);
        return session;
      } finally {
        refreshing = null;
      }
    })().then((session) => {
      onSessionChange(session);
      return session;
    });
  }
  return refreshing;
}

export interface RequestOptions {
  /** Send the bearer token and refresh on 401 (default true). */
  auth?: boolean;
  query?: Record<string, string | number | boolean | null | undefined>;
  headers?: Record<string, string>;
}

function withQuery(path: string, query?: RequestOptions['query']): string {
  if (!query) return path;
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `${path}${path.includes('?') ? '&' : '?'}${text}` : path;
}

export async function request<T>(method: string, path: string, body?: unknown, options: RequestOptions = {}): Promise<T> {
  const auth = options.auth ?? true;
  const url = withQuery(path, options.query);

  const attempt = () => {
    const headers: Record<string, string> = { Accept: 'application/json', ...options.headers };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (auth && accessToken) headers.Authorization = `Bearer ${accessToken}`;
    return send(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  };

  let response = await attempt();
  if (response.status === 401 && auth) {
    const session = await refreshSession();
    if (session) response = await attempt();
  }
  if (!response.ok) throw await toError(response);
  if (response.status === 204 || response.headers.get('content-length') === '0') return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const api = {
  get: <T>(path: string, options?: RequestOptions) => request<T>('GET', path, undefined, options),
  post: <T>(path: string, body?: unknown, options?: RequestOptions) => request<T>('POST', path, body, options),
  put: <T>(path: string, body?: unknown, options?: RequestOptions) => request<T>('PUT', path, body, options),
  patch: <T>(path: string, body?: unknown, options?: RequestOptions) => request<T>('PATCH', path, body, options),
  delete: <T>(path: string, body?: unknown, options?: RequestOptions) => request<T>('DELETE', path, body, options),
};

/** Logs out on the server (clears the refresh cookie) and forgets the access token. */
export async function logout(): Promise<void> {
  try {
    await send('/auth/logout', { method: 'POST', headers: CLIENT_HEADER });
  } finally {
    setSession(null);
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

/** A message that is always safe to show: the backend's own message, or a plain fallback. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  return 'Something went wrong. Try again.';
}
