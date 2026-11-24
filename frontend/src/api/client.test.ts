import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api, configureClient, hasAccessToken, onSessionChanged, refreshSession, setSession } from './client';

type Handler = (url: string, init: RequestInit) => Response | Promise<Response>;

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

const session = (token: string) => ({
  accessToken: token,
  expiresAt: '2030-01-01T00:00:00Z',
  user: { id: 'u1', email: 'a@b.c', fullName: 'A B', status: 'ACTIVE' },
});

describe('api client', () => {
  let calls: { url: string; init: RequestInit }[];
  let handler: Handler;

  beforeEach(() => {
    calls = [];
    setSession(null);
    onSessionChanged(() => {});
    configureClient({
      fetch: (async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        calls.push({ url, init: init ?? {} });
        return handler(url, init ?? {});
      }) as typeof fetch,
    });
  });

  afterEach(() => vi.restoreAllMocks());

  it('sends the bearer token and parses JSON', async () => {
    setSession(session('t1'));
    handler = () => json(200, { ok: true });
    await expect(api.get('/organizations')).resolves.toEqual({ ok: true });
    expect(calls[0].url).toBe('/api/v1/organizations');
    expect((calls[0].init.headers as Record<string, string>).Authorization).toBe('Bearer t1');
  });

  it('refreshes once on 401 and retries with the new token', async () => {
    setSession(session('old'));
    const changes: (string | null)[] = [];
    onSessionChanged((s) => changes.push(s?.accessToken ?? null));
    handler = (url, init) => {
      if (url.endsWith('/auth/refresh')) return json(200, session('new'));
      const auth = (init.headers as Record<string, string>).Authorization;
      return auth === 'Bearer new' ? json(200, { done: 1 }) : json(401, { code: 'UNAUTHENTICATED', message: 'no' });
    };
    await expect(api.get('/users/me')).resolves.toEqual({ done: 1 });
    expect(calls.map((c) => c.url)).toEqual(['/api/v1/users/me', '/api/v1/auth/refresh', '/api/v1/users/me']);
    expect((calls[1].init.headers as Record<string, string>)['X-PulseOps-Client']).toBe('web');
    expect(changes).toEqual(['new']);
  });

  it('shares one refresh between concurrent requests', async () => {
    let refreshes = 0;
    handler = (url, init) => {
      if (url.endsWith('/auth/refresh')) {
        refreshes++;
        return json(200, session('fresh'));
      }
      const auth = (init.headers as Record<string, string>).Authorization;
      return auth === 'Bearer fresh' ? json(200, {}) : json(401, {});
    };
    await Promise.all([api.get('/a'), api.get('/b'), api.get('/c')]);
    expect(refreshes).toBe(1);
  });

  it('reports a failed refresh as a signed-out session and surfaces the original 401', async () => {
    setSession(session('old'));
    const changes: (string | null)[] = [];
    onSessionChanged((s) => changes.push(s?.accessToken ?? null));
    handler = (url) =>
      url.endsWith('/auth/refresh')
        ? json(401, { code: 'SESSION_INVALID', message: 'Session expired' })
        : json(401, { code: 'UNAUTHENTICATED', message: 'Sign in' });
    const error = (await api.get('/users/me').catch((e) => e)) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('UNAUTHENTICATED');
    expect(changes).toEqual([null]);
    expect(hasAccessToken()).toBe(false);
  });

  it('turns backend errors into ApiError with field errors', async () => {
    handler = () =>
      json(400, { code: 'VALIDATION_FAILED', message: 'Invalid', fieldErrors: [{ field: 'name', message: 'must not be blank' }] });
    const error = (await api.post('/organizations', {}).catch((e) => e)) as ApiError;
    expect(error.status).toBe(400);
    expect(error.fieldMessage('name')).toBe('must not be blank');
  });

  it('reports an unreachable server as NETWORK, not as signed out', async () => {
    configureClient({ fetch: (async () => { throw new TypeError('Failed to fetch'); }) as typeof fetch });
    await expect(refreshSession()).rejects.toMatchObject({ code: 'NETWORK' });
  });

  it('returns undefined for 204 and adds query parameters, skipping empty ones', async () => {
    handler = () => new Response(null, { status: 204 });
    await expect(api.post('/x', undefined, { auth: false, query: { a: 1, b: '', c: undefined, d: 'x y' } })).resolves.toBeUndefined();
    expect(calls[0].url).toBe('/api/v1/x?a=1&d=x+y');
  });
});
