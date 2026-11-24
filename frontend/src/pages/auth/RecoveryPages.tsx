import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link as RouterLink, useNavigate, useSearchParams } from 'react-router-dom';
import { Alert, Box, Button, CircularProgress, Link, TextField, Typography } from '@mui/material';
import { errorMessage } from '../../api/client';
import { authApi } from '../../api/endpoints';
import { PasswordField } from '../../components/PasswordField';
import { AuthLayout } from '../../layout/AuthLayout';
import { passwordProblem } from '../../lib/format';
import { useFormError } from '../../lib/useFormError';

export function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const form = useFormError();

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    form.clear();
    try {
      await authApi.forgotPassword(email.trim());
      setSent(true);
    } catch (e) {
      form.setError(e);
    } finally {
      setBusy(false);
    }
  };

  if (sent) {
    return (
      <AuthLayout title="Check your email">
        <Typography>
          If an account exists for <strong>{email.trim()}</strong>, a link to reset the password is on its way. It works for
          30 minutes.
        </Typography>
        <Button component={RouterLink} to="/login" variant="contained" sx={{ mt: 3 }}>
          Back to sign in
        </Button>
      </AuthLayout>
    );
  }
  return (
    <AuthLayout title="Reset your password" intro="We will email you a link to choose a new password.">
      {form.general && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {form.general}
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
          autoFocus
          required
        />
        <Button type="submit" variant="contained" size="large" disabled={busy || !email}>
          Send reset link
        </Button>
      </Box>
      <Typography variant="body2" sx={{ mt: 3 }}>
        <Link component={RouterLink} to="/login">
          Back to sign in
        </Link>
      </Typography>
    </AuthLayout>
  );
}

export function ResetPasswordPage() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const navigate = useNavigate();
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const form = useFormError();
  const problem = password ? passwordProblem(password) : null;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (problem) return;
    setBusy(true);
    form.clear();
    try {
      await authApi.resetPassword(token, password);
      navigate('/login', { replace: true, state: { notice: 'Password changed. Sign in with your new password.' } });
    } catch (e) {
      form.setError(e);
    } finally {
      setBusy(false);
    }
  };

  if (!token) {
    return (
      <AuthLayout title="Link incomplete">
        <Typography>This reset link is missing its token. Open the link from the email again, or request a new one.</Typography>
        <Button component={RouterLink} to="/forgot-password" variant="contained" sx={{ mt: 3 }}>
          Request a new link
        </Button>
      </AuthLayout>
    );
  }
  return (
    <AuthLayout title="Choose a new password" intro="Every device signed in to your account will be signed out.">
      {form.general && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {form.general}{' '}
          <Link component={RouterLink} to="/forgot-password">
            Request a new link
          </Link>
        </Alert>
      )}
      <Box component="form" onSubmit={submit} noValidate sx={{ display: 'grid', gap: 2 }}>
        <PasswordField
          label="New password"
          autoComplete="new-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          error={!!(form.field('newPassword') ?? (password.length >= 12 ? problem : null))}
          helperText={form.field('newPassword') ?? problem ?? 'At least 12 characters.'}
          autoFocus
        />
        <Button type="submit" variant="contained" size="large" disabled={busy || !password}>
          Change password
        </Button>
      </Box>
    </AuthLayout>
  );
}

export function VerifyEmailPage() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [state, setState] = useState<'working' | 'done' | 'failed'>(token ? 'working' : 'failed');
  const [message, setMessage] = useState('This verification link is missing its token.');
  const started = useRef(false);

  useEffect(() => {
    // Tokens are single-use: guard against React's double-invoked effects in development.
    if (!token || started.current) return;
    started.current = true;
    authApi
      .verifyEmail(token)
      .then(() => setState('done'))
      .catch((e) => {
        setMessage(errorMessage(e));
        setState('failed');
      });
  }, [token]);

  if (state === 'working') {
    return (
      <AuthLayout title="Verifying your email">
        <CircularProgress size={24} />
      </AuthLayout>
    );
  }
  if (state === 'done') {
    return (
      <AuthLayout title="Email verified">
        <Typography>Your account is active.</Typography>
        <Button
          component={RouterLink}
          to="/login"
          state={{ notice: 'Email verified. Sign in to continue.' }}
          variant="contained"
          sx={{ mt: 3 }}
        >
          Sign in
        </Button>
      </AuthLayout>
    );
  }
  return (
    <AuthLayout title="Verification failed">
      <Typography>{message}</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
        Links expire after 24 hours and work once. Sign in to request a new one.
      </Typography>
      <Button component={RouterLink} to="/login" variant="contained" sx={{ mt: 3 }}>
        Go to sign in
      </Button>
    </AuthLayout>
  );
}
