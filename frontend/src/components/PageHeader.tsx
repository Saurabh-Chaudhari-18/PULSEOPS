import type { ReactNode } from 'react';
import { Box, Typography } from '@mui/material';

/** Title, one line of context and the page's main actions, aligned to the content's left edge. */
export function PageHeader({
  title,
  description,
  actions,
  above,
}: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  above?: ReactNode;
}) {
  return (
    <Box sx={{ mb: 3 }}>
      {above && <Box sx={{ mb: 1 }}>{above}</Box>}
      <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 2, flexWrap: 'wrap' }}>
        <Box sx={{ flex: '1 1 320px', minWidth: 0 }}>
          <Typography variant="h1">{title}</Typography>
          {description && (
            <Typography sx={{ mt: 0.5, maxWidth: '72ch' }} color="text.secondary">
              {description}
            </Typography>
          )}
        </Box>
        {actions && <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>{actions}</Box>}
      </Box>
    </Box>
  );
}
