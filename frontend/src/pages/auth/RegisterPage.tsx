import { useState, type FormEvent } from 'react';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import { Alert, Box, Button, Link, TextField, Typography } from '@mui/material';
import { authApi } from '../../api/endpoints';
import { PasswordField } from '../../components/PasswordField';
import { AuthLayout } from '../../layout/AuthLayout';
import { passwordProblem } from '../../lib/format';
import { useFormError } from '../../lib/useFormError';

export function RegisterPage() {
  const navigate = useNavigate();
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [touched, setTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  const [pending, setPending] = useState(false);
  const form = useFormError();

  const localProblem = password ? passwordProblem(password, email) : null;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setTouched(true);
    if (localProblem) return;
    setBusy(true);
    form.clear();
    try {
      const result = await authApi.register(fullName.trim(), email.trim(), password);
      if (result?.status === 'active') {
        navigate('/login', { state: { notice: 'Account created. Sign in to continue.', email: email.trim() } });
      } else {
        setPending(true);
      }
    } catch (e) {
      form.setError(e);
    } finally {
      setBusy(false);
    }
  };

  if (pending) {
    return (
      <AuthLayout title="Check your email">
        <Typography>
          If <strong>{email.trim()}</strong> can be registered, a verification link is on its way. Open it to activate
          your account, then sign in.
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
          Running PulseOps locally? In development mode the link is written to the backend log instead of being emailed.
        </Typography>
        <Button component={RouterLink} to="/login" variant="contained" sx={{ mt: 3 }}>
          Go to sign in
        </Button>
      </AuthLayout>
    );
  }

  const passwordMessage = form.field('password') ?? (touched || password.length >= 12 ? localProblem : null);
  return (
    <AuthLayout title="Create your account" intro="You can create an organization and invite your team next.">
      {form.general && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {form.general}
        </Alert>
      )}
      <Box component="form" onSubmit={submit} noValidate sx={{ display: 'grid', gap: 2 }}>
        <TextField
          label="Full name"
          autoComplete="name"
          value={fullName}
          onChange={(e) => setFullName(e.target.value)}
          error={!!form.field('fullName')}
          helperText={form.field('fullName')}
          required
          autoFocus
        />
        <TextField
          label="Work email"
          type="email"
          autoComplete="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          error={!!form.field('email')}
          helperText={form.field('email')}
          required
        />
        <PasswordField
          label="Password"
          autoComplete="new-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          onBlur={() => setTouched(true)}
          error={!!passwordMessage}
          helperText={passwordMessage ?? 'At least 12 characters. A short sentence works well.'}
          required
        />
        <Button type="submit" variant="contained" size="large" disabled={busy || !fullName || !email || !password}>
          {busy ? 'Creating account…' : 'Create account'}
        </Button>
      </Box>
      <Typography variant="body2" sx={{ mt: 3 }}>
        Already have an account?{' '}
        <Link component={RouterLink} to="/login">
          Sign in
        </Link>
      </Typography>
    </AuthLayout>
  );
}
