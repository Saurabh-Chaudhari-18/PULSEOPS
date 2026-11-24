import { useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Box,
  Button,
  Chip,
  Link,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import { credentialApi, projectApi, serviceApi } from '../../api/endpoints';
import type { Credential } from '../../api/types';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { EmptyState, ErrorNotice, Loading } from '../../components/Feedback';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { Signal } from '../../components/Signal';
import { P, useOrg } from '../../layout/OrgContext';
import { connectionView, environmentLabel, formatDate, isFresh, technologyLabel, timeAgo } from '../../lib/format';
import { fonts } from '../../theme';
import { IssueKeyDialog } from './KeyDialogs';
import { CreateServiceDialog } from './ProjectDialogs';

export function ProjectPage() {
  const { projectId = '' } = useParams();
  const { organization, can } = useOrg();
  const orgId = organization.id;
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [addingService, setAddingService] = useState(false);
  const [issuingKey, setIssuingKey] = useState(false);
  const [revoking, setRevoking] = useState<Credential | null>(null);
  const [confirm, setConfirm] = useState<'archive' | 'restore' | 'delete' | null>(null);

  const project = useQuery({
    queryKey: ['project', orgId, projectId],
    queryFn: () => projectApi.get(orgId, projectId),
  });
  const services = useQuery({
    queryKey: ['services', orgId, projectId],
    queryFn: () => serviceApi.listForProject(orgId, projectId),
  });
  const canKeys = can(P.credentialManage);
  const keys = useQuery({
    queryKey: ['credentials', orgId, projectId],
    queryFn: () => credentialApi.list(orgId, projectId),
    enabled: canKeys,
  });

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['project', orgId, projectId] });
    void queryClient.invalidateQueries({ queryKey: ['services', orgId, projectId] });
    void queryClient.invalidateQueries({ queryKey: ['credentials', orgId, projectId] });
    void queryClient.invalidateQueries({ queryKey: ['projects', orgId] });
  };

  if (project.isPending) return <Loading label="Loading project" />;
  if (project.isError) return <ErrorNotice error={project.error} onRetry={() => void project.refetch()} />;

  const p = project.data;
  const archived = p.status === 'ARCHIVED';
  const canManage = can(P.projectManage);
  const serviceList = services.data?.content ?? [];
  const serviceName = (id: string | null) => serviceList.find((s) => s.id === id)?.name ?? 'a removed service';

  return (
    <>
      <PageHeader
        above={
          <Link component={RouterLink} to={`/o/${orgId}/projects`} variant="body2">
            Projects and services
          </Link>
        }
        title={
          <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
            {p.name}
            {p.environment && <Chip size="small" label={environmentLabel[p.environment]} />}
            {archived && <Chip size="small" color="warning" variant="outlined" label="Archived" />}
          </Box>
        }
        description={[p.description, p.techStack].filter(Boolean).join(' — ') || undefined}
        actions={
          canManage && (
            <>
              {archived ? (
                <Button variant="outlined" onClick={() => setConfirm('restore')}>
                  Restore project
                </Button>
              ) : (
                <Button variant="outlined" onClick={() => setConfirm('archive')}>
                  Archive project
                </Button>
              )}
              <Button color="error" onClick={() => setConfirm('delete')}>
                Delete
              </Button>
            </>
          )
        }
      />

      <Panel
        title="Services"
        description="Each service is one running application that sends its own telemetry."
        flush
        actions={
          canManage &&
          !archived && (
            <Button size="small" variant="contained" startIcon={<AddIcon />} onClick={() => setAddingService(true)}>
              Add service
            </Button>
          )
        }
      >
        {services.error && (
          <Box sx={{ p: 2 }}>
            <ErrorNotice error={services.error} onRetry={() => void services.refetch()} />
          </Box>
        )}
        {services.isPending ? (
          <Box sx={{ px: 2.5 }}>
            <Loading label="Loading services" />
          </Box>
        ) : serviceList.length === 0 ? (
          <EmptyState
            title="No services yet"
            action={
              canManage &&
              !archived && (
                <Button variant="contained" startIcon={<AddIcon />} onClick={() => setAddingService(true)}>
                  Add service
                </Button>
              )
            }
          >
            Add the first application of this project, then follow its connection guide.
          </EmptyState>
        ) : (
          <TableContainer>
            <Table>
              <TableHead>
                <TableRow>
                  <TableCell>Service</TableCell>
                  <TableCell>Technology</TableCell>
                  <TableCell>Connection</TableCell>
                  <TableCell>Last data</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {serviceList.map((s) => {
                  const view = connectionView(s.connection?.status, isFresh(s.connection?.lastTelemetryAt));
                  return (
                    <TableRow key={s.id} hover>
                      <TableCell>
                        <Link
                          component={RouterLink}
                          to={`/o/${orgId}/services/${s.id}${s.connection?.firstTelemetryAt ? '' : '/connection'}`}
                          sx={{ fontWeight: 600, fontSize: 14 }}
                        >
                          {s.name}
                        </Link>
                        {s.status === 'ARCHIVED' && (
                          <Chip size="small" variant="outlined" label="Archived" sx={{ ml: 1 }} />
                        )}
                      </TableCell>
                      <TableCell>{s.technology ? technologyLabel[s.technology] : ''}</TableCell>
                      <TableCell>
                        <Signal health={view.health} label={view.label} />
                      </TableCell>
                      <TableCell>
                        {s.connection?.lastTelemetryAt ? timeAgo(s.connection.lastTelemetryAt) : 'none yet'}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Panel>

      {canKeys && (
        <Panel
          title="Integration keys"
          description="Applications use a key to send telemetry to this project. Keys are shown once, when created."
          flush
          actions={
            !archived && (
              <Button size="small" variant="outlined" startIcon={<AddIcon />} onClick={() => setIssuingKey(true)}>
                Create key
              </Button>
            )
          }
        >
          {keys.error && (
            <Box sx={{ p: 2 }}>
              <ErrorNotice error={keys.error} onRetry={() => void keys.refetch()} />
            </Box>
          )}
          {keys.isPending ? (
            <Box sx={{ px: 2.5 }}>
              <Loading label="Loading keys" />
            </Box>
          ) : keys.data && keys.data.length === 0 ? (
            <EmptyState title="No keys yet">
              Create one when you connect a service; the connection guide can do it for you.
            </EmptyState>
          ) : (
            keys.data && (
              <TableContainer>
                <Table>
                  <TableHead>
                    <TableRow>
                      <TableCell>Name</TableCell>
                      <TableCell>Key</TableCell>
                      <TableCell>Can send for</TableCell>
                      <TableCell>Status</TableCell>
                      <TableCell>Last used</TableCell>
                      <TableCell align="right" />
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {keys.data.map((k) => (
                      <TableRow key={k.id}>
                        <TableCell sx={{ fontWeight: 500 }}>{k.name}</TableCell>
                        <TableCell sx={{ fontFamily: fonts.mono, fontSize: 12.5 }}>{k.keyPrefix}…</TableCell>
                        <TableCell>{k.serviceId ? serviceName(k.serviceId) : 'Any service'}</TableCell>
                        <TableCell>
                          <Signal
                            health={k.status === 'ACTIVE' ? 'ok' : 'idle'}
                            label={
                              k.status === 'ACTIVE'
                                ? k.expiresAt
                                  ? `Active until ${formatDate(k.expiresAt)}`
                                  : 'Active'
                                : k.status === 'REVOKED'
                                  ? 'Revoked'
                                  : 'Expired'
                            }
                          />
                        </TableCell>
                        <TableCell>{timeAgo(k.lastUsedAt)}</TableCell>
                        <TableCell align="right">
                          {k.status === 'ACTIVE' && (
                            <Button size="small" color="error" onClick={() => setRevoking(k)}>
                              Revoke
                            </Button>
                          )}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )
          )}
        </Panel>
      )}

      <Typography variant="caption" component="p">
        Created {formatDate(p.createdAt)}
      </Typography>

      <CreateServiceDialog
        orgId={orgId}
        projectId={projectId}
        open={addingService}
        onClose={() => setAddingService(false)}
        onCreated={(service) => {
          setAddingService(false);
          refresh();
          navigate(`/o/${orgId}/services/${service.id}/connection`);
        }}
      />
      <IssueKeyDialog
        orgId={orgId}
        projectId={projectId}
        services={serviceList.filter((s) => s.status === 'ACTIVE')}
        open={issuingKey}
        onClose={() => setIssuingKey(false)}
        onIssued={refresh}
      />
      <ConfirmDialog
        open={!!revoking}
        title={`Revoke "${revoking?.name}"?`}
        confirmLabel="Revoke key"
        destructive
        onClose={() => setRevoking(null)}
        onConfirm={async () => {
          if (revoking) await credentialApi.revoke(orgId, revoking.id);
          refresh();
        }}
      >
        Applications using this key stop being able to send data immediately. This cannot be undone; you can create a
        new key afterwards.
      </ConfirmDialog>
      <ConfirmDialog
        open={confirm === 'archive'}
        title={`Archive ${p.name}?`}
        confirmLabel="Archive project"
        onClose={() => setConfirm(null)}
        onConfirm={async () => {
          await projectApi.archive(orgId, projectId);
          refresh();
        }}
      >
        Its services stop accepting telemetry and no new services or keys can be added. Existing data stays, and you can
        restore the project later.
      </ConfirmDialog>
      <ConfirmDialog
        open={confirm === 'restore'}
        title={`Restore ${p.name}?`}
        confirmLabel="Restore project"
        onClose={() => setConfirm(null)}
        onConfirm={async () => {
          await projectApi.restore(orgId, projectId);
          refresh();
        }}
      >
        The project becomes active again. Services you archived separately stay archived.
      </ConfirmDialog>
      <ConfirmDialog
        open={confirm === 'delete'}
        title={`Delete ${p.name}?`}
        confirmLabel="Delete project"
        destructive
        typeToConfirm={p.name}
        onClose={() => setConfirm(null)}
        onConfirm={async () => {
          await projectApi.remove(orgId, projectId, p.name);
          void queryClient.invalidateQueries({ queryKey: ['projects', orgId] });
          navigate(`/o/${orgId}/projects`, { replace: true });
        }}
      >
        Its services are deleted and every key of the project is revoked, so connected applications stop sending data at
        once.
      </ConfirmDialog>
    </>
  );
}
