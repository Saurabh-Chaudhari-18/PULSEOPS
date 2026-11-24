import type { HealthStatus } from '../api/types';
import { healthView } from '../lib/numbers';
import { Signal } from './Signal';

export function HealthSignal({ status }: { status: HealthStatus }) {
  const view = healthView[status] ?? healthView.NO_DATA;
  return <Signal health={view.health} label={view.label} />;
}
