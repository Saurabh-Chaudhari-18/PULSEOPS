import { ToggleButton, ToggleButtonGroup } from '@mui/material';
import { RANGE_PRESETS } from '../lib/timeRange';

export function TimeRangePicker({ value, onChange }: { value: string; onChange: (key: string) => void }) {
  return (
    <ToggleButtonGroup
      size="small"
      exclusive
      value={value}
      onChange={(_, key: string | null) => key && onChange(key)}
      aria-label="Time range"
      sx={{ bgcolor: 'background.paper', '& .MuiToggleButton-root': { px: 1.5, py: 0.5, textTransform: 'none' } }}
    >
      {RANGE_PRESETS.map((p) => (
        <ToggleButton key={p.key} value={p.key}>
          {p.label}
        </ToggleButton>
      ))}
    </ToggleButtonGroup>
  );
}
