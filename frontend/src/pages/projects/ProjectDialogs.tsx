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
} from '@mui/material';
import { projectApi, serviceApi } from '../../api/endpoints';
import type { AppService, Environment, Project, Technology } from '../../api/types';
import { environmentLabel, technologyLabel } from '../../lib/format';
import { useFormError } from '../../lib/useFormError';

export function CreateProjectDialog({
  orgId,
  open,
  onClose,
  onCreated,
}: {
  orgId: string;
  open: boolean;
  onClose: () => void;
  onCreated: (project: Project) => void;
}) {
  const [name, setName] = useState('');
  const [environment, setEnvironment] = useState<Environment>('PRODUCTION');
  const [description, setDescription] = useState('');
  const [techStack, setTechStack] = useState('');
  const [busy, setBusy] = useState(false);
  const form = useFormError();

  const reset = () => {
    setName('');
    setEnvironment('PRODUCTION');
    setDescription('');
    setTechStack('');
    form.clear();
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    form.clear();
    try {
      const project = await projectApi.create(orgId, {
        name: name.trim(),
        environment,
        description: description.trim() || undefined,
        techStack: techStack.trim() || undefined,
      });
      reset();
      onCreated(project);
    } catch (e) {
      form.setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onClose={() => !busy && onClose()} maxWidth="sm" fullWidth>
      <Box component="form" onSubmit={submit} noValidate>
        <DialogTitle>New project</DialogTitle>
        <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
          {form.general && <Alert severity="error">{form.general}</Alert>}
          <TextField
            label="Project name"
            placeholder="Checkout"
            value={name}
            onChange={(e) => setName(e.target.value)}
            error={!!form.field('name')}
            helperText={form.field('name') ?? 'Usually one product or application, made of one or more services.'}
            autoFocus
            required
          />
          <TextField
            select
            label="Environment"
            value={environment}
            onChange={(e) => setEnvironment(e.target.value as Environment)}
          >
            {(Object.keys(environmentLabel) as Environment[]).map((env) => (
              <MenuItem key={env} value={env}>
                {environmentLabel[env]}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label="Technology (optional)"
            placeholder="Java 21, Spring Boot, PostgreSQL"
            value={techStack}
            onChange={(e) => setTechStack(e.target.value)}
          />
          <TextField
            label="Description (optional)"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            multiline
            minRows={2}
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={busy || !name.trim()}>
            Create project
          </Button>
        </DialogActions>
      </Box>
    </Dialog>
  );
}

export function CreateServiceDialog({
  orgId,
  projectId,
  open,
  onClose,
  onCreated,
}: {
  orgId: string;
  projectId: string;
  open: boolean;
  onClose: () => void;
  onCreated: (service: AppService) => void;
}) {
  const [name, setName] = useState('');
  const [technology, setTechnology] = useState<Technology>('JAVA_SPRING_BOOT');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const form = useFormError();

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    form.clear();
    try {
      const service = await serviceApi.create(
        orgId,
        projectId,
        name.trim(),
        technology,
        description.trim() || undefined,
      );
      setName('');
      setDescription('');
      onCreated(service);
    } catch (e) {
      form.setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onClose={() => !busy && onClose()} maxWidth="sm" fullWidth>
      <Box component="form" onSubmit={submit} noValidate>
        <DialogTitle>Add a service</DialogTitle>
        <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
          {form.general && <Alert severity="error">{form.general}</Alert>}
          <TextField
            label="Service name"
            placeholder="order-service"
            value={name}
            onChange={(e) => setName(e.target.value)}
            error={!!form.field('name')}
            helperText={
              form.field('name') ??
              'Use the name your application reports itself as (for OpenTelemetry, the service.name), so data matches up.'
            }
            autoFocus
            required
          />
          <TextField
            select
            label="Technology"
            value={technology}
            onChange={(e) => setTechnology(e.target.value as Technology)}
          >
            {(Object.keys(technologyLabel) as Technology[]).map((t) => (
              <MenuItem key={t} value={t}>
                {technologyLabel[t]}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label="Description (optional)"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            multiline
            minRows={2}
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={busy || !name.trim()}>
            Add service
          </Button>
        </DialogActions>
      </Box>
    </Dialog>
  );
}
