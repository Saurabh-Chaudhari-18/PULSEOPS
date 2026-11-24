import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert, Box, Button, TextField, Typography } from '@mui/material';
import { userApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { PasswordField } from '../../components/PasswordField';
import { passwordProblem } from '../../lib/format';
import { useFormError } from '../../lib/useFormError';

export function AccountPage() {
  const { user, updateUser, endSession } = useAuth();
  const navigate = useNavigate();
  const [fullName, setFullName] = useState(user?.fullName ?? '');
  const [saved, setSaved] = useState(false);
  const profile = useFormError();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const password = useFormError();
  const [busy, setBusy] = useState(false);
  const [signOutAll, setSignOutAll] = useState(false);
  const problem = next ? passwordProblem(next, user?.email) : null;

  const saveProfile = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setSaved(false);
    profile.clear();
    try {
      const updated = await userApi.update(fullName.trim());
      updateUser({ fullName: updated.fullName });
      setSaved(true);
    } catch (e) {
      profile.setError(e);
    } finally {
      setBusy(false);
    }
  };

  const changePassword = async (event: FormEvent) => {
    event.preventDefault();
    if (problem) return;
    setBusy(true);
    password.clear();
    try {
      await userApi.changePassword(current, next);
      endSession();
      navigate('/login', {
        replace: true,
        state: { notice: 'Password changed. Sign in again on each device.', email: user?.email },
      });
    } catch (e) {
      password.setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader title="Account settings" description={user?.email} />
      <Box sx={{ maxWidth: 560 }}>
        <Panel title="Profile">
          {profile.general && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {profile.general}
            </Alert>
          )}
          {saved && (
            <Alert severity="success" sx={{ mb: 2 }}>
              Name saved.
            </Alert>
          )}
          <Box component="form" onSubmit={saveProfile} noValidate sx={{ display: 'grid', gap: 2 }}>
            <TextField
              label="Full name"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              error={!!profile.field('fullName')}
              helperText={profile.field('fullName')}
            />
            <Box>
              <Button
                type="submit"
                variant="contained"
                disabled={busy || !fullName.trim() || fullName.trim() === user?.fullName}
              >
                Save name
              </Button>
            </Box>
          </Box>
        </Panel>

        <Panel title="Password" description="Changing it signs you out everywhere, including here.">
          {password.general && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {password.general}
            </Alert>
          )}
          <Box component="form" onSubmit={changePassword} noValidate sx={{ display: 'grid', gap: 2 }}>
            <PasswordField
              label="Current password"
              autoComplete="current-password"
              value={current}
              onChange={(e) => setCurrent(e.target.value)}
              error={!!password.field('currentPassword')}
              helperText={password.field('currentPassword')}
            />
            <PasswordField
              label="New password"
              autoComplete="new-password"
              value={next}
              onChange={(e) => setNext(e.target.value)}
              error={!!(password.field('newPassword') ?? (next.length >= 12 ? problem : null))}
              helperText={password.field('newPassword') ?? problem ?? 'At least 12 characters.'}
            />
            <Box>
              <Button type="submit" variant="contained" disabled={busy || !current || !next}>
                Change password
              </Button>
            </Box>
          </Box>
        </Panel>

        <Panel title="Sessions">
          <Typography variant="body2" sx={{ mb: 2 }}>
            Lost a device or signed in on a shared computer? Sign out of PulseOps on every device at once.
          </Typography>
          <Button variant="outlined" color="error" onClick={() => setSignOutAll(true)}>
            Sign out everywhere
          </Button>
        </Panel>
      </Box>
      <ConfirmDialog
        open={signOutAll}
        title="Sign out everywhere?"
        confirmLabel="Sign out everywhere"
        destructive
        onClose={() => setSignOutAll(false)}
        onConfirm={async () => {
          await userApi.revokeAllSessions();
          endSession();
          navigate('/login', { replace: true, state: { notice: 'Signed out on every device.' } });
        }}
      >
        Every session ends, including this one. You will need to sign in again.
      </ConfirmDialog>
    </>
  );
}
