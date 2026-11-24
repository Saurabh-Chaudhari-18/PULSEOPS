import { describe, expect, it } from 'vitest';
import { connectionView, passwordProblem, timeAgo } from './format';

describe('timeAgo', () => {
  const now = new Date('2026-11-24T12:00:00Z');
  it('reads naturally', () => {
    expect(timeAgo(null, now)).toBe('never');
    expect(timeAgo('2026-11-24T11:59:40Z', now)).toBe('just now');
    expect(timeAgo('2026-11-24T11:56:00Z', now)).toBe('4 min ago');
    expect(timeAgo('2026-11-24T09:00:00Z', now)).toBe('3 h ago');
    expect(timeAgo('2026-11-23T10:00:00Z', now)).toBe('yesterday');
    expect(timeAgo('2026-11-21T12:00:00Z', now)).toBe('3 days ago');
  });
});

describe('connectionView', () => {
  it('distinguishes live, silent and broken connections', () => {
    expect(connectionView('CONNECTED', true)).toEqual({ health: 'ok', label: 'Connected' });
    expect(connectionView('CONNECTED', false).health).toBe('watch');
    expect(connectionView('WAITING_FOR_DATA').label).toBe('Waiting for data');
    expect(connectionView('CONNECTION_ERROR').health).toBe('critical');
    expect(connectionView(null).label).toBe('Not connected');
  });
});

describe('passwordProblem', () => {
  it('mirrors the backend rules', () => {
    expect(passwordProblem('short')).toMatch(/12/);
    expect(passwordProblem('aaaaaaaaaaaaaa')).toMatch(/repeat/);
    expect(passwordProblem('me@example.com', 'ME@example.com')).toMatch(/email/);
    expect(passwordProblem('a'.repeat(73) + 'b')).toMatch(/72/);
    expect(passwordProblem('correct horse battery')).toBeNull();
  });
});
