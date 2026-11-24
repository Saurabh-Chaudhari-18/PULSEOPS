import type { ReactNode } from 'react';
import { Box, Paper, Typography } from '@mui/material';

/** One headline number with a label and a line of context. Proportional figures at this size. */
export function StatTile({ label, value, detail }: { label: string; value: ReactNode; detail?: ReactNode }) {
  return (
    <Paper variant="outlined" sx={{ p: 2, minWidth: 0 }}>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography sx={{ fontSize: 24, fontWeight: 600, color: 'text.primary', mt: 0.5, lineHeight: 1.2 }}>
        {value}
      </Typography>
      {detail && (
        <Box sx={{ mt: 0.75 }}>
          <Typography variant="caption" component="div">
            {detail}
          </Typography>
        </Box>
      )}
    </Paper>
  );
}

export function StatRow({ children }: { children: ReactNode }) {
  return (
    <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))', mb: 3 }}>
      {children}
    </Box>
  );
}
