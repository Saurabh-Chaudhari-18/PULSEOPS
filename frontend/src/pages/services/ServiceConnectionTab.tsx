import { useState, type ReactNode } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, ButtonBase, Link, Typography } from '@mui/material';
import { credentialApi, integrationApi, serviceApi } from '../../api/endpoints';
import type { IntegrationType, IssuedCredential } from '../../api/types';
import { errorMessage } from '../../api/client';
import { CodeBlock } from '../../components/CodeBlock';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ErrorNotice, Loading } from '../../components/Feedback';
import { Panel } from '../../components/Panel';
import { Signal } from '../../components/Signal';
import { P, useOrg } from '../../layout/OrgContext';
import { connectionView, formatDateTime, integrationLabel, timeAgo } from '../../lib/format';
import { tokens } from '../../theme';
import { IssuedKeyNotice } from '../projects/KeyDialogs';

const METHODS: { type: IntegrationType; title: string; detail: string }[] = [
  {
    type: 'JAVA_AGENT',
    title: 'Java agent',
    detail: 'Attach the OpenTelemetry Java agent at startup. No code changes; captures HTTP, database calls and logs.',
  },
  {
    type: 'OTLP',
    title: 'OpenTelemetry (OTLP)',
    detail: 'Point an existing OpenTelemetry SDK or Collector at PulseOps. Works for any language.',
  },
  {
    type: 'REST_API',
    title: 'REST API',
    detail: 'Send events and metrics yourself as JSON. For custom integrations and scripts.',
  },
];

/** One step of the guide. The steps really are a sequence, so they carry numbers. */
function Step({ n, title, done, children }: { n: number; title: string; done?: boolean; children: ReactNode }) {
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: '32px 1fr',
        gap: 2,
        py: 2.5,
        '& + &': { borderTop: 1, borderColor: 'divider' },
      }}
    >
      <Box
        sx={{
          width: 28,
          height: 28,
          borderRadius: '50%',
          display: 'grid',
          placeItems: 'center',
          fontSize: 13,
          fontWeight: 600,
          bgcolor: done ? tokens.ok : tokens.cobaltSoft,
          color: done ? '#FFFFFF' : tokens.cobalt,
        }}
        aria-hidden
      >
        {done ? '✓' : n}
      </Box>
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="h3" sx={{ mb: 1, lineHeight: '28px' }}>
          {title}
        </Typography>
        {children}
      </Box>
    </Box>
  );
}

export function ServiceConnectionTab() {
  const { serviceId = '' } = useParams();
  const { organization, can } = useOrg();
  const orgId = organization.id;
  const queryClient = useQueryClient();
  const [issued, setIssued] = useState<IssuedCredential | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState<'archive' | 'restore' | 'disable' | null>(null);

  const service = useQuery({
    queryKey: ['service', orgId, serviceId],
    queryFn: () => serviceApi.get(orgId, serviceId),
  });
  const status = useQuery({
    queryKey: ['integration', orgId, serviceId],
    queryFn: () => integrationApi.status(orgId, serviceId),
    // Poll quickly while waiting for the first data, slowly once it flows.
    refetchInterval: (query) => (query.state.data?.receivingNow ? 30_000 : 5_000),
  });
  const chosen = status.data?.integrationType ?? null;
  const instructions = useQuery({
    queryKey: ['instructions', orgId, serviceId, chosen],
    queryFn: () => integrationApi.instructions(orgId, serviceId, chosen as IntegrationType),
    enabled: !!chosen,
  });

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['service', orgId, serviceId] });
    void queryClient.invalidateQueries({ queryKey: ['integration', orgId, serviceId] });
    void queryClient.invalidateQueries({ queryKey: ['services', orgId] });
    void queryClient.invalidateQueries({ queryKey: ['credentials', orgId] });
  };

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setActionError(null);
    try {
      await action();
      refresh();
    } catch (e) {
      setActionError(e);
    } finally {
      setBusy(false);
    }
  };

  if (service.isPending || status.isPending) return <Loading label="Loading service" />;
  if (service.isError) return <ErrorNotice error={service.error} onRetry={() => void service.refetch()} />;
  if (status.isError) return <ErrorNotice error={status.error} onRetry={() => void status.refetch()} />;

  const s = service.data;
  const st = status.data;
  const archived = s.status === 'ARCHIVED';
  const disabled = st.status === 'DISABLED';
  const canManage = can(P.projectManage);
  const canKeys = can(P.credentialManage);
  const view = connectionView(st.status, st.receivingNow);
  const connected = st.status === 'CONNECTED';

  return (
    <>
      {canManage && (
        <Box sx={{ display: 'flex', gap: 1, justifyContent: 'flex-end', mb: 2, flexWrap: 'wrap' }}>
          {!archived &&
            (disabled ? (
              <Button
                variant="outlined"
                disabled={busy}
                onClick={() => run(() => integrationApi.enable(orgId, serviceId))}
              >
                Turn ingestion on
              </Button>
            ) : (
              <Button variant="outlined" disabled={busy} onClick={() => setConfirm('disable')}>
                Turn ingestion off
              </Button>
            ))}
          {archived ? (
            <Button variant="outlined" onClick={() => setConfirm('restore')}>
              Restore service
            </Button>
          ) : (
            <Button color="error" onClick={() => setConfirm('archive')}>
              Archive
            </Button>
          )}
        </Box>
      )}

      {actionError !== null && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setActionError(null)}>
          {errorMessage(actionError)}
        </Alert>
      )}

      <Panel title="Connection">
        <Box sx={{ display: 'flex', gap: 4, flexWrap: 'wrap', alignItems: 'flex-start' }}>
          <Box sx={{ minWidth: 220 }}>
            <Signal health={view.health} label={view.label} live={connected && st.receivingNow} />
            <Typography variant="body2" sx={{ mt: 1, maxWidth: '48ch' }}>
              {st.nextStep}
            </Typography>
          </Box>
          <Box
            component="dl"
            sx={{ m: 0, display: 'grid', gridTemplateColumns: 'auto 1fr', columnGap: 3, rowGap: 0.5 }}
          >
            <Typography component="dt" variant="caption">
              Method
            </Typography>
            <Typography component="dd" variant="body2" sx={{ m: 0 }}>
              {chosen ? integrationLabel[chosen] : 'Not chosen'}
            </Typography>
            <Typography component="dt" variant="caption">
              First data
            </Typography>
            <Typography component="dd" variant="body2" sx={{ m: 0 }}>
              {st.firstTelemetryAt ? formatDateTime(st.firstTelemetryAt) : 'none yet'}
            </Typography>
            <Typography component="dt" variant="caption">
              Latest data
            </Typography>
            <Typography component="dd" variant="body2" sx={{ m: 0 }}>
              {st.lastTelemetryAt ? timeAgo(st.lastTelemetryAt) : 'none yet'}
            </Typography>
            <Typography component="dt" variant="caption">
              Usable keys
            </Typography>
            <Typography component="dd" variant="body2" sx={{ m: 0 }}>
              {st.usableKeys}
            </Typography>
          </Box>
        </Box>
        {st.lastError && (
          <Alert severity="error" variant="outlined" sx={{ mt: 2 }}>
            Last rejected data: {st.lastError}
          </Alert>
        )}
      </Panel>

      {!archived && (
        <Panel
          title={connected ? 'Connection guide' : `Connect ${s.name}`}
          description="Four steps. This page updates by itself when data arrives."
        >
          <Step n={1} title="Choose how the service sends data" done={!!chosen}>
            <Box sx={{ display: 'grid', gap: 1.25, gridTemplateColumns: { xs: '1fr', md: 'repeat(3, 1fr)' } }}>
              {METHODS.map((m) => {
                const selected = chosen === m.type;
                return (
                  <ButtonBase
                    key={m.type}
                    disabled={!canManage || busy}
                    onClick={() => !selected && run(() => integrationApi.choose(orgId, serviceId, m.type))}
                    aria-pressed={selected}
                    sx={{
                      display: 'block',
                      textAlign: 'left',
                      p: 1.75,
                      borderRadius: 1.5,
                      border: `1.5px solid ${selected ? tokens.cobalt : tokens.rule}`,
                      bgcolor: selected ? tokens.cobaltSoft : 'transparent',
                      '&:hover': { borderColor: selected ? tokens.cobalt : tokens.ruleStrong },
                    }}
                  >
                    <Typography variant="h4" sx={{ color: selected ? tokens.cobalt : tokens.ink }}>
                      {m.title}
                    </Typography>
                    <Typography variant="body2" sx={{ mt: 0.5 }}>
                      {m.detail}
                    </Typography>
                  </ButtonBase>
                );
              })}
            </Box>
            {!canManage && (
              <Typography variant="caption" component="p" sx={{ mt: 1 }}>
                Your role cannot change the connection method.
              </Typography>
            )}
          </Step>

          <Step n={2} title="Get an integration key" done={st.usableKeys > 0 || !!issued}>
            {issued ? (
              <IssuedKeyNotice issued={issued} />
            ) : st.usableKeys > 0 ? (
              <Typography variant="body2">
                This service can use {st.usableKeys === 1 ? 'one active key' : `${st.usableKeys} active keys`}. Use the
                key you saved when it was created; if it is lost, create a new one and revoke the old one on the{' '}
                <Link component={RouterLink} to={`/o/${orgId}/projects/${s.projectId}`}>
                  project page
                </Link>
                .
              </Typography>
            ) : (
              <Typography variant="body2">No active key can send data for this service yet.</Typography>
            )}
            {canKeys && !issued && (
              <Button
                sx={{ mt: 1.5 }}
                variant={st.usableKeys > 0 ? 'text' : 'contained'}
                disabled={busy}
                onClick={() =>
                  run(async () => {
                    const result = await credentialApi.issue(orgId, s.projectId, `${s.name} key`, serviceId);
                    setIssued(result);
                  })
                }
              >
                {st.usableKeys > 0 ? 'Create another key for this service' : 'Create a key for this service'}
              </Button>
            )}
            {!canKeys && st.usableKeys === 0 && (
              <Typography variant="caption" component="p" sx={{ mt: 1 }}>
                Ask an admin to create a key: your role cannot.
              </Typography>
            )}
          </Step>

          <Step n={3} title="Set up the application" done={connected}>
            {!chosen ? (
              <Typography variant="body2" color="text.secondary">
                Choose a method in step 1 to see the instructions.
              </Typography>
            ) : instructions.isError ? (
              <ErrorNotice error={instructions.error} onRetry={() => void instructions.refetch()} />
            ) : !instructions.data ? (
              <Loading label="Loading instructions" />
            ) : !instructions.data.available ? (
              <Alert severity="info">{instructions.data.unavailableReason}</Alert>
            ) : (
              <Box>
                {instructions.data.endpoint && (
                  <Typography variant="body2" sx={{ mb: 1 }}>
                    Data goes to <code>{instructions.data.endpoint}</code>
                  </Typography>
                )}
                {instructions.data.steps.map((step) => (
                  <Box key={step.title} sx={{ mt: 2 }}>
                    <Typography variant="h4">{step.title}</Typography>
                    <Typography variant="body2" sx={{ mt: 0.25, maxWidth: '78ch' }}>
                      {step.description}
                    </Typography>
                    {step.snippet && <CodeBlock code={step.snippet} />}
                  </Box>
                ))}
              </Box>
            )}
          </Step>

          <Step n={4} title="Check that data arrives" done={connected && st.receivingNow}>
            {connected && st.receivingNow ? (
              <Signal health="ok" label={`Data is arriving. Latest ${timeAgo(st.lastTelemetryAt)}.`} live />
            ) : (
              <>
                <Signal health="watch" label="Waiting for the first data from this service" live />
                <Typography variant="body2" sx={{ mt: 1 }}>
                  Start the application and send it a few requests. Checking every 5 seconds.
                </Typography>
              </>
            )}
          </Step>
        </Panel>
      )}

      <ConfirmDialog
        open={confirm === 'disable'}
        title={`Turn ingestion off for ${s.name}?`}
        confirmLabel="Turn ingestion off"
        onClose={() => setConfirm(null)}
        onConfirm={async () => {
          await integrationApi.disable(orgId, serviceId);
          refresh();
        }}
      >
        PulseOps will reject this service's telemetry until you turn ingestion on again. Keys stay valid.
      </ConfirmDialog>
      <ConfirmDialog
        open={confirm === 'archive'}
        title={`Archive ${s.name}?`}
        confirmLabel="Archive service"
        destructive
        onClose={() => setConfirm(null)}
        onConfirm={async () => {
          await serviceApi.archive(orgId, serviceId);
          refresh();
        }}
      >
        The service stops accepting telemetry. Its data stays, and you can restore it later.
      </ConfirmDialog>
      <ConfirmDialog
        open={confirm === 'restore'}
        title={`Restore ${s.name}?`}
        confirmLabel="Restore service"
        onClose={() => setConfirm(null)}
        onConfirm={async () => {
          await serviceApi.restore(orgId, serviceId);
          refresh();
        }}
      >
        The service becomes active again. Turn ingestion on afterwards if it was switched off.
      </ConfirmDialog>
    </>
  );
}
