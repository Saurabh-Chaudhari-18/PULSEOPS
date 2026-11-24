import type { ReactNode } from 'react';
import { Box, Paper, Typography } from '@mui/material';

/** A bordered section of a page with an optional title row. */
export function Panel({
  title,
  description,
  actions,
  children,
  flush = false,
}: {
  title?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  /** No inner padding: for tables that run edge to edge. */
  flush?: boolean;
}) {
  return (
    <Paper variant="outlined" sx={{ mb: 3, overflow: 'hidden' }}>
      {(title || actions) && (
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 2,
            px: 2.5,
            py: 1.75,
            borderBottom: 1,
            borderColor: 'divider',
            flexWrap: 'wrap',
          }}
        >
          <Box sx={{ flex: '1 1 240px', minWidth: 0 }}>
            {title && <Typography variant="h3">{title}</Typography>}
            {description && (
              <Typography variant="body2" color="text.secondary">
                {description}
              </Typography>
            )}
          </Box>
          {actions && <Box sx={{ display: 'flex', gap: 1 }}>{actions}</Box>}
        </Box>
      )}
      <Box sx={flush ? undefined : { p: 2.5 }}>{children}</Box>
    </Paper>
  );
}
