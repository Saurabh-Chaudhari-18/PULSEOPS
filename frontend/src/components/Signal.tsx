import { Box, Typography } from '@mui/material';
import type { Health } from '../lib/format';
import { tokens } from '../theme';

const colour: Record<Health, string> = {
  ok: tokens.ok,
  watch: tokens.watch,
  critical: tokens.critical,
  idle: tokens.muted,
};

/**
 * Health is always a dot and a word, never colour alone. A live "ok" dot carries a slow ring
 * so a working connection is distinguishable from a stale one at a glance.
 */
export function Signal({ health, label, live = false }: { health: Health; label: string; live?: boolean }) {
  const c = colour[health];
  return (
    <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 1 }}>
      <Box
        component="span"
        sx={{
          position: 'relative',
          width: 8,
          height: 8,
          borderRadius: '50%',
          bgcolor: c,
          flexShrink: 0,
          ...(live && {
            '&::after': {
              content: '""',
              position: 'absolute',
              inset: -4,
              borderRadius: '50%',
              border: `1.5px solid ${c}`,
              animation: 'pulseops-ring 2.4s ease-out infinite',
            },
            '@keyframes pulseops-ring': {
              from: { opacity: 0.7, transform: 'scale(0.6)' },
              to: { opacity: 0, transform: 'scale(1.5)' },
            },
          }),
        }}
      />
      <Typography component="span" variant="body2" sx={{ color: health === 'idle' ? tokens.muted : tokens.ink }}>
        {label}
      </Typography>
    </Box>
  );
}
