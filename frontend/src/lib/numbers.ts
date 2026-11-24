import type { HealthStatus } from '../api/types';
import type { Health } from './format';

const DASH = '—';

/** Latency: "0.7 ms", "48 ms", "1.52 s". Absent data is a dash, never "0 ms". */
export function fmtMs(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return DASH;
  if (value < 10) return `${value.toFixed(1)} ms`;
  if (value < 1000) return `${Math.round(value)} ms`;
  return `${(value / 1000).toFixed(value < 10_000 ? 2 : 1)} s`;
}

/** A fraction 0–1 as a percentage: "0%", "<0.1%", "2.4%", "100%". */
export function fmtPct(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return DASH;
  if (value === 0) return '0%';
  if (value < 0.001) return '<0.1%';
  const pct = value * 100;
  return `${pct >= 10 ? Math.round(pct) : pct.toFixed(1)}%`;
}

/** Throughput: below one per second reads better per minute. */
export function fmtRate(perSecond: number | null | undefined, unit = 'req'): string {
  if (perSecond === null || perSecond === undefined || Number.isNaN(perSecond)) return DASH;
  if (perSecond === 0) return `0 ${unit}/s`;
  if (perSecond < 1) return `${(perSecond * 60).toFixed(perSecond * 60 < 10 ? 1 : 0)} ${unit}/min`;
  return `${perSecond < 10 ? perSecond.toFixed(1) : Math.round(perSecond)} ${unit}/s`;
}

/** Counts: 1,284 · 12.9K · 4.2M. */
export function fmtCount(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return DASH;
  if (Math.abs(value) < 10_000) return Math.round(value).toLocaleString('en-US');
  if (Math.abs(value) < 1_000_000) return `${(value / 1000).toFixed(1).replace(/\.0$/, '')}K`;
  return `${(value / 1_000_000).toFixed(1).replace(/\.0$/, '')}M`;
}

export function fmtBytes(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return DASH;
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let v = value;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v < 10 && i > 0 ? v.toFixed(1) : Math.round(v)} ${units[i]}`;
}

export const healthView: Record<HealthStatus, { health: Health; label: string }> = {
  HEALTHY: { health: 'ok', label: 'Healthy' },
  WARNING: { health: 'watch', label: 'Warning' },
  CRITICAL: { health: 'critical', label: 'Critical' },
  NO_DATA: { health: 'idle', label: 'No data' },
};
