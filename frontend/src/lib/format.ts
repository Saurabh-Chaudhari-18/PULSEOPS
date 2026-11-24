import type { ConnectionStatus, Environment, IntegrationType, Role, Technology } from '../api/types';

/** "just now", "4 min ago", "3 h ago", "2 days ago", or a date for anything older than a week. */
export function timeAgo(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) return 'never';
  const then = new Date(iso);
  const seconds = Math.round((now.getTime() - then.getTime()) / 1000);
  if (Number.isNaN(seconds)) return '';
  if (seconds < 0) return 'just now';
  if (seconds < 45) return 'just now';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.round(hours / 24);
  if (days <= 7) return days === 1 ? 'yesterday' : `${days} days ago`;
  return formatDate(iso);
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '';
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '';
  return new Date(iso).toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export const environmentLabel: Record<Environment, string> = {
  DEVELOPMENT: 'Development',
  TESTING: 'Testing',
  STAGING: 'Staging',
  PRODUCTION: 'Production',
  OTHER: 'Other',
};

export const technologyLabel: Record<Technology, string> = {
  JAVA_SPRING_BOOT: 'Java / Spring Boot',
  PYTHON: 'Python',
  NODEJS: 'Node.js',
  DOTNET: '.NET',
  GO: 'Go',
  CUSTOM_REST: 'Custom (REST)',
  OPENTELEMETRY: 'OpenTelemetry',
  OTHER: 'Other',
};

export const integrationLabel: Record<IntegrationType, string> = {
  JAVA_AGENT: 'Java agent',
  OTLP: 'OpenTelemetry (OTLP)',
  REST_API: 'REST API',
};

export const roleLabel: Record<Role, string> = {
  OWNER: 'Owner',
  ADMIN: 'Admin',
  MEMBER: 'Member',
  VIEWER: 'Viewer',
};

export const roleDescription: Record<Role, string> = {
  OWNER: 'Everything, including deleting the organization',
  ADMIN: 'Everything except deleting or renaming the organization',
  MEMBER: 'Sees all data and works on incidents',
  VIEWER: 'Sees all data, changes nothing',
};

export type Health = 'ok' | 'watch' | 'critical' | 'idle';

export interface ConnectionView {
  health: Health;
  label: string;
}

/** The backend's freshness window: a connected service with nothing for 15 minutes is "not receiving". */
export const FRESH_MINUTES = 15;

export function isFresh(lastTelemetryAt: string | null | undefined, now: Date = new Date()): boolean {
  if (!lastTelemetryAt) return false;
  return now.getTime() - new Date(lastTelemetryAt).getTime() <= FRESH_MINUTES * 60_000;
}

/** How a service's connection reads at a glance; "connected" but silent for 15 min is a watch state. */
export function connectionView(status: ConnectionStatus | null | undefined, receivingNow?: boolean): ConnectionView {
  switch (status) {
    case 'CONNECTED':
      return receivingNow === false ? { health: 'watch', label: 'Connected, no recent data' } : { health: 'ok', label: 'Connected' };
    case 'WAITING_FOR_DATA':
      return { health: 'watch', label: 'Waiting for data' };
    case 'CONNECTION_ERROR':
      return { health: 'critical', label: 'Connection error' };
    case 'DISABLED':
      return { health: 'idle', label: 'Ingestion off' };
    default:
      return { health: 'idle', label: 'Not connected' };
  }
}

/** Mirrors the backend's password rules so people learn them before submitting. */
export function passwordProblem(password: string, email?: string): string | null {
  if (password.length < 12) return 'Use at least 12 characters.';
  if (new TextEncoder().encode(password).length > 72) return 'Use at most 72 bytes (about 72 plain characters).';
  if (email && password.toLowerCase() === email.toLowerCase()) return 'Do not use your email address as the password.';
  if (/^(.)\1+$/.test(password)) return 'Do not repeat a single character.';
  return null;
}
