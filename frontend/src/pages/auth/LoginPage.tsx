import { useState, type FormEvent } from 'react';
import { Link as RouterLink, useLocation, useNavigate } from 'react-router-dom';
import { Alert, Box, Button, Link, TextField, Typography } from '@mui/material';
import { ApiError } from '../../api/client';
import { authApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { PasswordField } from '../../components/PasswordField';
import { AuthLayout } from '../../layout/AuthLayout';
import { useFormError } from '../../lib/useFormError';

interface LoginState {
  from?: string;
  notice?: string;
  email?: string;
}

export function LoginPage() {
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const state = (useLocation().state ?? {}) as LoginState;
  const [email, setEmail] = useState(state.email ?? '');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [resent, setResent] = useState(false);
  const form = useFormError();

  const unverified = form.error instanceof ApiError && form.error.code === 'EMAIL_NOT_VERIFIED';

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    form.clear();
    setResent(false);
    try {
      await signIn(email.trim(), password);
      navigate(state.from && state.from !== '/login' ? state.from : '/', { replace: true });
    } catch (e) {
      form.setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout title="Sign in" intro="Use the email address and password of your PulseOps account.">
      {state.notice && !form.error && (
        <Alert severity="success" sx={{ mb: 2 }}>
          {state.notice}
        </Alert>
      )}
      {form.general && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {form.error instanceof ApiError && form.error.code === 'INVALID_CREDENTIALS'
            ? 'The email address or password is incorrect.'
            : form.general}
          {unverified && !resent && (
            <Box sx={{ mt: 1 }}>
              <Link
                component="button"
                type="button"
                onClick={async () => {
                  await authApi.resendVerification(email.trim()).catch(() => undefined);
                  setResent(true);
                }}
              >
                Send the verification email again
              </Link>
            </Box>
          )}
          {resent && <Box sx={{ mt: 1 }}>If the account needs verifying, a new link is on its way.</Box>}
        </Alert>
      )}
      <Box component="form" onSubmit={submit} noValidate sx={{ display: 'grid', gap: 2 }}>
        <TextField
          label="Email"
          type="email"
          autoComplete="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          error={!!form.field('email')}
          helperText={form.field('email')}
          required
          autoFocus
        />
        <PasswordField
          label="Password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
        <Box sx={{ display: 'flex', justifyContent: 'flex-end', mt: -1 }}>
          <Link component={RouterLink} to="/forgot-password" variant="body2">
            Forgot your password?
          </Link>
        </Box>
        <Button type="submit" variant="contained" size="large" disabled={busy || !email || !password}>
          {busy ? 'Signing in…' : 'Sign in'}
        </Button>
      </Box>
      <Typography variant="body2" sx={{ mt: 3 }}>
        New to PulseOps?{' '}
        <Link component={RouterLink} to="/register">
          Create an account
        </Link>
      </Typography>
    </AuthLayout>
  );
}
