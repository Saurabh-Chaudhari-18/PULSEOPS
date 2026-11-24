import { useState, type FormEvent } from 'react';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Link, Paper, TextField, Typography } from '@mui/material';
import { orgApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { PulseMark } from '../../components/PulseMark';
import { useFormError } from '../../lib/useFormError';

export function CreateOrganizationPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { signOut, user } = useAuth();
  const organizations = useQuery({ queryKey: ['organizations'], queryFn: orgApi.list });
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const form = useFormError();
  const first = organizations.data?.length === 0;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    form.clear();
    try {
      const org = await orgApi.create(name.trim(), description.trim() || undefined);
      await queryClient.invalidateQueries({ queryKey: ['organizations'] });
      navigate(`/o/${org.id}/projects`, { replace: true });
    } catch (e) {
      form.setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Box sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center', p: 3 }}>
      <Box sx={{ width: '100%', maxWidth: 480 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.25, mb: 3 }}>
          <PulseMark />
          <Typography sx={{ fontWeight: 600, fontSize: 16 }}>PulseOps</Typography>
        </Box>
        <Paper variant="outlined" sx={{ p: { xs: 3, sm: 4 } }}>
          <Typography variant="h1">{first ? `Welcome, ${user?.fullName?.split(' ')[0] ?? ''}` : 'New organization'}</Typography>
          <Typography color="text.secondary" sx={{ mt: 0.75, mb: 3 }}>
            {first
              ? 'Start with an organization: usually your company or team. Its projects, services and data are visible only to its members.'
              : 'Each organization has its own members, projects and data.'}
          </Typography>
          {form.general && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {form.general}
            </Alert>
          )}
          <Box component="form" onSubmit={submit} noValidate sx={{ display: 'grid', gap: 2 }}>
            <TextField
              label="Organization name"
              placeholder="Acme Payments"
              value={name}
              onChange={(e) => setName(e.target.value)}
              error={!!form.field('name')}
              helperText={form.field('name')}
              autoFocus
              required
            />
            <TextField
              label="Description (optional)"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              multiline
              minRows={2}
            />
            <Box sx={{ display: 'flex', gap: 1, justifyContent: 'flex-end' }}>
              {!first && <Button onClick={() => navigate(-1)}>Cancel</Button>}
              <Button type="submit" variant="contained" disabled={busy || name.trim().length < 2}>
                Create organization
              </Button>
            </Box>
          </Box>
        </Paper>
        <Typography variant="body2" sx={{ mt: 2 }}>
          {first ? (
            <>
              Waiting for an invitation instead? Ask an admin to add <strong>{user?.email}</strong>, then{' '}
              <Link component="button" onClick={() => void organizations.refetch().then(() => navigate('/'))}>
                check again
              </Link>{' '}
              or{' '}
              <Link component="button" onClick={() => void signOut().then(() => navigate('/login'))}>
                sign out
              </Link>
              .
            </>
          ) : (
            <Link component={RouterLink} to="/">
              Back to PulseOps
            </Link>
          )}
        </Typography>
      </Box>
    </Box>
  );
}
