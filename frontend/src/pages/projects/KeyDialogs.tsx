import { useState, type FormEvent } from 'react';
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  TextField,
  Typography,
} from '@mui/material';
import { credentialApi } from '../../api/endpoints';
import type { AppService, IssuedCredential } from '../../api/types';
import { CodeBlock } from '../../components/CodeBlock';
import { useFormError } from '../../lib/useFormError';

/** Shows a freshly issued key. It is never retrievable again, so the dialog says so plainly. */
export function IssuedKeyNotice({ issued }: { issued: IssuedCredential }) {
  return (
    <Box>
      <Alert severity="warning" sx={{ mb: 1.5 }}>
        Copy this key now. PulseOps stores only a hash of it and cannot show it again.
      </Alert>
      <CodeBlock code={issued.key} label="Copy key" />
      <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
        Keep it in an environment variable or a secret store, never in source control.
      </Typography>
    </Box>
  );
}

export function IssueKeyDialog({
  orgId,
  projectId,
  services,
  presetServiceId,
  open,
  onClose,
  onIssued,
}: {
  orgId: string;
  projectId: string;
  services: AppService[];
  presetServiceId?: string;
  open: boolean;
  onClose: () => void;
  onIssued: () => void;
}) {
  const [name, setName] = useState('');
  const [scope, setScope] = useState<string>(presetServiceId ?? '');
  const [expiry, setExpiry] = useState<string>('');
  const [issued, setIssued] = useState<IssuedCredential | null>(null);
  const [busy, setBusy] = useState(false);
  const form = useFormError();

  const close = () => {
    if (busy) return;
    setName('');
    setExpiry('');
    setIssued(null);
    form.clear();
    onClose();
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    form.clear();
    try {
      const days = expiry ? Number(expiry) : undefined;
      const result = await credentialApi.issue(orgId, projectId, name.trim(), scope || undefined, days);
      setIssued(result);
      onIssued();
    } catch (e) {
      form.setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onClose={issued ? undefined : close} maxWidth="sm" fullWidth>
      {issued ? (
        <>
          <DialogTitle>Key created: {issued.credential.name}</DialogTitle>
          <DialogContent>
            <IssuedKeyNotice issued={issued} />
          </DialogContent>
          <DialogActions sx={{ px: 3, pb: 2 }}>
            <Button variant="contained" onClick={close}>
              I have copied the key
            </Button>
          </DialogActions>
        </>
      ) : (
        <Box component="form" onSubmit={submit} noValidate>
          <DialogTitle>Create an integration key</DialogTitle>
          <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
            {form.general && <Alert severity="error">{form.general}</Alert>}
            <TextField
              label="Key name"
              placeholder="order-service production"
              value={name}
              onChange={(e) => setName(e.target.value)}
              error={!!form.field('name')}
              helperText={form.field('name') ?? 'So you can tell keys apart when rotating or revoking them.'}
              autoFocus
              required
            />
            <TextField
              select
              label="Can send data for"
              value={scope}
              onChange={(e) => setScope(e.target.value)}
              helperText="A key for one service is safer: if it leaks, only that service's data can be faked."
            >
              <MenuItem value="">Any service in this project</MenuItem>
              {services.map((s) => (
                <MenuItem key={s.id} value={s.id}>
                  Only {s.name}
                </MenuItem>
              ))}
            </TextField>
            <TextField select label="Expires" value={expiry} onChange={(e) => setExpiry(e.target.value)}>
              <MenuItem value="">Never (revoke it when no longer needed)</MenuItem>
              <MenuItem value="30">In 30 days</MenuItem>
              <MenuItem value="90">In 90 days</MenuItem>
              <MenuItem value="365">In a year</MenuItem>
            </TextField>
          </DialogContent>
          <DialogActions sx={{ px: 3, pb: 2 }}>
            <Button onClick={close} disabled={busy}>
              Cancel
            </Button>
            <Button type="submit" variant="contained" disabled={busy || !name.trim()}>
              Create key
            </Button>
          </DialogActions>
        </Box>
      )}
    </Dialog>
  );
}
