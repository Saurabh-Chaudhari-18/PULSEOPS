import type { ReactNode } from 'react';
import { Alert, Box, Button, CircularProgress, Typography } from '@mui/material';
import { errorMessage } from '../api/client';

export function Loading({ label = 'Loading' }: { label?: string }) {
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 4, color: 'text.secondary' }}>
      <CircularProgress size={18} />
      <Typography variant="body2">{label}…</Typography>
    </Box>
  );
}

export function ErrorNotice({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <Alert
      severity="error"
      variant="outlined"
      action={
        onRetry && (
          <Button color="inherit" size="small" onClick={onRetry}>
            Try again
          </Button>
        )
      }
      sx={{ mb: 2 }}
    >
      {errorMessage(error)}
    </Alert>
  );
}

/** An empty list is a prompt to act: say what belongs here and offer the action. */
export function EmptyState({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <Box sx={{ py: 5, px: 2, textAlign: 'center' }}>
      <Typography variant="h3">{title}</Typography>
      {children && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.75, maxWidth: '56ch', mx: 'auto' }}>
          {children}
        </Typography>
      )}
      {action && <Box sx={{ mt: 2 }}>{action}</Box>}
    </Box>
  );
}
