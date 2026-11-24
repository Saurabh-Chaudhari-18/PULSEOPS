import type { ReactNode } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { Box, Button, CircularProgress, Typography } from '@mui/material';
import { useAuth } from './AuthContext';

/** Gate for signed-in screens: waits for the session restore, then redirects to sign-in. */
export function RequireAuth() {
  const { status, retryRestore } = useAuth();
  const location = useLocation();

  if (status === 'restoring') {
    return (
      <Box sx={{ display: 'grid', placeItems: 'center', minHeight: '100vh' }}>
        <CircularProgress size={28} aria-label="Restoring your session" />
      </Box>
    );
  }
  if (status === 'offline') {
    return (
      <Box sx={{ display: 'grid', placeItems: 'center', minHeight: '100vh', p: 3 }}>
        <Box sx={{ maxWidth: 420 }}>
          <Typography variant="h2" gutterBottom>
            Cannot reach the PulseOps server
          </Typography>
          <Typography sx={{ mb: 2 }}>
            Check that the backend is running and that the web app points at it, then try again.
          </Typography>
          <Button variant="contained" onClick={retryRestore}>
            Try again
          </Button>
        </Box>
      </Box>
    );
  }
  if (status === 'signedOut') {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return <Outlet />;
}

/** Sign-in screens send people who already have a session into the app. */
export function RedirectIfSignedIn({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const from = (useLocation().state as { from?: string } | null)?.from;
  if (status === 'signedIn') return <Navigate to={from && from !== '/login' ? from : '/'} replace />;
  return <>{children}</>;
}
