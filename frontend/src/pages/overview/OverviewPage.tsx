import { Link as RouterLink, useSearchParams } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import {
  Box,
  Button,
  Link,
  MenuItem,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { monitoringApi, projectApi } from '../../api/endpoints';
import type { HealthStatus, ServiceOverview } from '../../api/types';
import { EmptyState, ErrorNotice, Loading } from '../../components/Feedback';
import { HealthSignal } from '../../components/HealthSignal';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { Signal } from '../../components/Signal';
import { StatRow, StatTile } from '../../components/StatTile';
import { TimeRangePicker } from '../../components/TimeRangePicker';
import { P, useOrg } from '../../layout/OrgContext';
import { timeAgo } from '../../lib/format';
import { fmtCount, fmtMs, fmtPct, fmtRate, healthView } from '../../lib/numbers';
import { useTimeRange } from '../../lib/timeRange';

const HEALTH_ORDER: HealthStatus[] = ['CRITICAL', 'WARNING', 'HEALTHY', 'NO_DATA'];

function ServiceRows({ orgId, services, range }: { orgId: string; services: ServiceOverview[]; range: string }) {
  const suffix = range === '1h' ? '' : `?range=${range}`;
  return (
    <TableContainer>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell>Service</TableCell>
            <TableCell>Health</TableCell>
            <TableCell align="right">Throughput</TableCell>
            <TableCell align="right">Errors</TableCell>
            <TableCell align="right">p95 latency</TableCell>
            <TableCell>Last data</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {services.map((s) => (
            <TableRow key={s.serviceId} hover>
              <TableCell>
                <Link
                  component={RouterLink}
                  to={`/o/${orgId}/services/${s.serviceId}${suffix}`}
                  sx={{ fontWeight: 600, fontSize: 14 }}
                >
                  {s.serviceName}
                </Link>
                <Typography variant="caption" component="div">
                  {s.projectName}
                </Typography>
              </TableCell>
              <TableCell>
                <HealthSignal status={s.health.status} />
              </TableCell>
              <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                {fmtRate(s.requestsPerSecond)}
              </TableCell>
              <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                {fmtPct(s.errorRate)}
              </TableCell>
              <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                {fmtMs(s.p95Ms)}
              </TableCell>
              <TableCell>{s.lastTelemetryAt ? timeAgo(s.lastTelemetryAt) : 'never'}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

export function OverviewPage() {
  const { organization, can } = useOrg();
  const orgId = organization.id;
  const { preset, setPreset, range, refetchInterval } = useTimeRange();
  const [params, setParams] = useSearchParams();
  const projectId = params.get('project') ?? '';

  const projects = useQuery({
    queryKey: ['projects', orgId, 'ACTIVE', ''],
    queryFn: () => projectApi.list(orgId, 'ACTIVE'),
    enabled: can(P.projectRead),
  });
  const overview = useQuery({
    queryKey: ['overview', orgId, preset.key, projectId],
    queryFn: () => monitoringApi.overview(orgId, range(), projectId || undefined),
    refetchInterval,
    placeholderData: keepPreviousData,
  });

  const setProject = (id: string) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        if (id) next.set('project', id);
        else next.delete('project');
        return next;
      },
      { replace: true },
    );

  const o = overview.data;
  return (
    <>
      <PageHeader title="Overview" description="How every connected service is doing right now, worst first." />
      <Box sx={{ display: 'flex', gap: 1.5, mb: 2.5, flexWrap: 'wrap', alignItems: 'center' }}>
        <TimeRangePicker value={preset.key} onChange={setPreset} />
        {projects.data && projects.data.content.length > 1 && (
          <TextField
            select
            value={projectId}
            onChange={(e) => setProject(e.target.value)}
            sx={{ width: 220 }}
            slotProps={{ select: { displayEmpty: true } }}
            aria-label="Project"
          >
            <MenuItem value="">All projects</MenuItem>
            {projects.data.content.map((p) => (
              <MenuItem key={p.id} value={p.id}>
                {p.name}
              </MenuItem>
            ))}
          </TextField>
        )}
      </Box>

      {overview.isError && <ErrorNotice error={overview.error} onRetry={() => void overview.refetch()} />}
      {!o ? (
        overview.isPending && <Loading label="Loading overview" />
      ) : o.services === 0 ? (
        <Paper variant="outlined">
          <EmptyState
            title="Nothing to monitor yet"
            action={
              <Button variant="contained" component={RouterLink} to={`/o/${orgId}/projects`}>
                Go to projects and services
              </Button>
            }
          >
            Create a project, add a service and connect it. Its numbers appear here within a minute of the first data.
          </EmptyState>
        </Paper>
      ) : (
        <Box sx={{ opacity: overview.isFetching && overview.isPlaceholderData ? 0.6 : 1, transition: 'opacity 120ms' }}>
          <StatRow>
            <StatTile
              label="Services"
              value={`${o.connectedServices} of ${o.services} connected`}
              detail={
                <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
                  {HEALTH_ORDER.filter((h) => (o.servicesByHealth[h] ?? 0) > 0).map((h) => (
                    <Signal
                      key={h}
                      health={healthView[h].health}
                      label={`${o.servicesByHealth[h]} ${healthView[h].label.toLowerCase()}`}
                    />
                  ))}
                </Box>
              }
            />
            <StatTile
              label="Requests"
              value={fmtRate(o.requestsPerSecond)}
              detail={`${fmtCount(o.requests)} in ${preset.label.toLowerCase()}`}
            />
            <StatTile label="Server errors" value={fmtPct(o.errorRate)} detail="Share of requests answered with 5xx" />
            <StatTile label="p95 latency" value={fmtMs(o.p95Ms)} detail="95% of requests were faster" />
            <StatTile
              label="Database p95"
              value={fmtMs(o.database.p95Ms)}
              detail={
                o.database.calls > 0
                  ? `${fmtCount(o.database.calls)} calls, ${fmtCount(o.database.slowCalls)} slow, ${fmtPct(o.database.errorRate)} failed`
                  : 'No database calls recorded'
              }
            />
          </StatRow>

          {o.attention.length > 0 && (
            <Panel title="Needs attention" description="Services past a health threshold in this period." flush>
              {o.attention.map((s) => (
                <Box
                  key={s.serviceId}
                  sx={{
                    display: 'flex',
                    gap: 2,
                    alignItems: 'flex-start',
                    px: 2.5,
                    py: 1.75,
                    '& + &': { borderTop: 1, borderColor: 'divider' },
                  }}
                >
                  <Box sx={{ minWidth: 120 }}>
                    <HealthSignal status={s.health.status} />
                  </Box>
                  <Box sx={{ flex: 1, minWidth: 0 }}>
                    <Link component={RouterLink} to={`/o/${orgId}/services/${s.serviceId}`} sx={{ fontWeight: 600 }}>
                      {s.serviceName}
                    </Link>
                    <Typography variant="caption" sx={{ ml: 1 }}>
                      {s.projectName}
                    </Typography>
                    {s.health.reasons.map((r) => (
                      <Typography key={r} variant="body2">
                        {r}
                      </Typography>
                    ))}
                  </Box>
                </Box>
              ))}
            </Panel>
          )}

          <Panel title="All services" flush>
            <ServiceRows orgId={orgId} services={o.allServices} range={preset.key} />
          </Panel>
        </Box>
      )}
    </>
  );
}
