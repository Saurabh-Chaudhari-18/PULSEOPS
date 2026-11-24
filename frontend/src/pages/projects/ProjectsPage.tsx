import { useState } from 'react';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import {
  Box,
  Button,
  Chip,
  InputAdornment,
  Link,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import SearchIcon from '@mui/icons-material/Search';
import { projectApi } from '../../api/endpoints';
import { EmptyState, ErrorNotice, Loading } from '../../components/Feedback';
import { PageHeader } from '../../components/PageHeader';
import { Signal } from '../../components/Signal';
import { P, useOrg } from '../../layout/OrgContext';
import { environmentLabel, formatDate } from '../../lib/format';
import { CreateProjectDialog } from './ProjectDialogs';

export function ProjectsPage() {
  const { organization, can } = useOrg();
  const navigate = useNavigate();
  const [status, setStatus] = useState<'ACTIVE' | 'ARCHIVED'>('ACTIVE');
  const [search, setSearch] = useState('');
  const [creating, setCreating] = useState(false);
  const orgId = organization.id;

  const projects = useQuery({
    queryKey: ['projects', orgId, status, search.trim()],
    queryFn: () => projectApi.list(orgId, status, search.trim() || undefined),
    placeholderData: keepPreviousData,
  });
  const canManage = can(P.projectManage);

  return (
    <>
      <PageHeader
        title="Projects and services"
        description="A project groups the services of one application. Each service sends its own traces, logs and metrics."
        actions={
          canManage && (
            <Button variant="contained" startIcon={<AddIcon />} onClick={() => setCreating(true)}>
              New project
            </Button>
          )
        }
      />

      <Box sx={{ display: 'flex', gap: 1.5, mb: 2, flexWrap: 'wrap', alignItems: 'center' }}>
        <TextField
          placeholder="Search projects"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          sx={{ maxWidth: 320 }}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <SearchIcon fontSize="small" />
                </InputAdornment>
              ),
            },
          }}
        />
        <ToggleButtonGroup
          size="small"
          exclusive
          value={status}
          onChange={(_, value) => value && setStatus(value)}
          aria-label="Project status"
        >
          <ToggleButton value="ACTIVE">Active</ToggleButton>
          <ToggleButton value="ARCHIVED">Archived</ToggleButton>
        </ToggleButtonGroup>
      </Box>

      {projects.error && <ErrorNotice error={projects.error} onRetry={() => void projects.refetch()} />}
      {projects.isPending ? (
        <Loading label="Loading projects" />
      ) : projects.data && projects.data.content.length === 0 ? (
        <Paper variant="outlined">
          {search.trim() ? (
            <EmptyState title="No matching projects">Nothing matches “{search.trim()}”.</EmptyState>
          ) : status === 'ARCHIVED' ? (
            <EmptyState title="No archived projects" />
          ) : (
            <EmptyState
              title="Create your first project"
              action={
                canManage && (
                  <Button variant="contained" startIcon={<AddIcon />} onClick={() => setCreating(true)}>
                    New project
                  </Button>
                )
              }
            >
              Then add its services and connect them. Data starts flowing as soon as a service sends its first trace.
            </EmptyState>
          )}
        </Paper>
      ) : (
        projects.data && (
          <TableContainer component={Paper} variant="outlined">
            <Table>
              <TableHead>
                <TableRow>
                  <TableCell>Project</TableCell>
                  <TableCell>Environment</TableCell>
                  <TableCell>Services connected</TableCell>
                  <TableCell>Created</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {projects.data.content.map((project) => {
                  const all = project.serviceCount;
                  const connected = project.connectedServiceCount;
                  const health = all === 0 ? 'idle' : connected === all ? 'ok' : connected === 0 ? 'idle' : 'watch';
                  return (
                    <TableRow key={project.id} hover>
                      <TableCell>
                        <Link
                          component={RouterLink}
                          to={`/o/${orgId}/projects/${project.id}`}
                          sx={{ fontWeight: 600, fontSize: 14 }}
                        >
                          {project.name}
                        </Link>
                        {project.description && (
                          <Typography variant="caption" component="div" sx={{ maxWidth: '60ch' }} noWrap>
                            {project.description}
                          </Typography>
                        )}
                      </TableCell>
                      <TableCell>
                        {project.environment && (
                          <Chip
                            size="small"
                            variant={project.environment === 'PRODUCTION' ? 'filled' : 'outlined'}
                            label={environmentLabel[project.environment]}
                          />
                        )}
                      </TableCell>
                      <TableCell>
                        <Signal
                          health={health}
                          label={all === 0 ? 'No services yet' : `${connected} of ${all} connected`}
                        />
                      </TableCell>
                      <TableCell>{formatDate(project.createdAt)}</TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </TableContainer>
        )
      )}

      <CreateProjectDialog
        orgId={orgId}
        open={creating}
        onClose={() => setCreating(false)}
        onCreated={(project) => {
          setCreating(false);
          navigate(`/o/${orgId}/projects/${project.id}`);
        }}
      />
    </>
  );
}
