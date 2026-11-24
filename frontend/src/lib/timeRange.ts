import { useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';

export interface RangePreset {
  key: string;
  label: string;
  minutes: number;
}

export const RANGE_PRESETS: RangePreset[] = [
  { key: '15m', label: '15 min', minutes: 15 },
  { key: '1h', label: '1 hour', minutes: 60 },
  { key: '6h', label: '6 hours', minutes: 360 },
  { key: '24h', label: '24 hours', minutes: 1440 },
  { key: '7d', label: '7 days', minutes: 10080 },
];

export const DEFAULT_RANGE = '1h';

export function presetFor(key: string | null | undefined): RangePreset {
  return RANGE_PRESETS.find((p) => p.key === key) ?? RANGE_PRESETS.find((p) => p.key === DEFAULT_RANGE)!;
}

/** The concrete window for a preset, ending now. Computed when a query runs, so refetches move forward. */
export function rangeFor(preset: RangePreset, now: Date = new Date()): { from: string; to: string } {
  const to = new Date(now);
  const from = new Date(now.getTime() - preset.minutes * 60_000);
  return { from: from.toISOString(), to: to.toISOString() };
}

/** Short ranges are live: refresh every minute; long ranges every five. */
export function refreshInterval(preset: RangePreset): number {
  return preset.minutes <= 360 ? 60_000 : 300_000;
}

/** The selected range lives in the URL (?range=6h), so a link shows the same view. */
export function useTimeRange() {
  const [params, setParams] = useSearchParams();
  const preset = presetFor(params.get('range'));
  const setPreset = useCallback(
    (key: string) => {
      setParams(
        (current) => {
          const next = new URLSearchParams(current);
          if (key === DEFAULT_RANGE) next.delete('range');
          else next.set('range', key);
          return next;
        },
        { replace: true },
      );
    },
    [setParams],
  );
  return { preset, setPreset, range: () => rangeFor(preset), refetchInterval: refreshInterval(preset) };
}
