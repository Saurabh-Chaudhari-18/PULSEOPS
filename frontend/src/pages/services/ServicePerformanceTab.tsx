import { useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Chip,
  InputAdornment,
  Link,
  MenuItem,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import SearchIcon from '@mui/icons-material/Search';
import { monitoringApi } from '../../api/endpoints';
import { ChartGrid, TimeSeriesChart, toRows } from '../../components/charts/TimeSeriesChart';
import { SERIES } from '../../components/charts/chartTheme';
import { ErrorNotice, Loading } from '../../components/Feedback';
import { HealthSignal } from '../../components/HealthSignal';
import { Panel } from '../../components/Panel';
import { StatRow, StatTile } from '../../components/StatTile';
import { TimeRangePicker } from '../../components/TimeRangePicker';
import { useOrg } from '../../layout/OrgContext';
import { timeAgo } from '../../lib/format';
import { fmtBytes, fmtCount, fmtMs, fmtPct, fmtRate } from '../../lib/numbers';
import { useTimeRange } from '../../lib/timeRange';
import { fonts, tokens } from '../../theme';

const SORTS = [
  { key: 'requests', label: 'Most requests' },
  { key: 'p95', label: 'Slowest (p95)' },
  { key: 'errors', label: 'Most errors' },
  { key: 'error_rate', label: 'Highest error rate' },
  { key: 'route', label: 'Route A–Z' },
];

const num = { fontVariantNumeric: 'tabular-nums' } as const;

export function ServicePerformanceTab() {
  const { serviceId = '' } = useParams();
  const { organization } = useOrg();
  const orgId = organization.id;
  const { preset, setPreset, range, refetchInterval } = useTimeRange();
  const [sort, setSort] = useState('requests');
  const [search, setSearch] = useState('');
  const [focus, setFocus] = useState<{ method: string; route: string } | null>(null);

  const summary = useQuery({
    queryKey: ['svc-summary', orgId, serviceId, preset.key],
    queryFn: () => monitoringApi.summary(orgId, serviceId, range()),
    refetchInterval,
    placeholderData: keepPreviousData,
  });
  const series = useQuery({
    queryKey: ['svc-series', orgId, serviceId, preset.key, focus?.method, focus?.route],
    queryFn: () => monitoringApi.timeseries(orgId, serviceId, range(), focus ?? undefined),
    refetchInterval,
    placeholderData: keepPreviousData,
  });
  const endpoints = useQuery({
    queryKey: ['svc-endpoints', orgId, serviceId, preset.key, sort, search.trim()],
    queryFn: () => monitoringApi.endpoints(orgId, serviceId, range(), sort, search.trim() || undefined),
    refetchInterval,
    placeholderData: keepPreviousData,
  });
  const runtime = useQuery({
    queryKey: ['svc-runtime', orgId, serviceId, preset.key],
    queryFn: () => monitoringApi.runtime(orgId, serviceId, range()),
    refetchInterval,
    placeholderData: keepPreviousData,
  });

  const s = summary.data;
  const points = series.data?.points ?? [];
  const latencyRows = toRows(points, { p50: (p) => p.p50Ms, p95: (p) => p.p95Ms, p99: (p) => p.p99Ms });
  const throughputRows = toRows(points, { rps: (p) => p.requestsPerSecond });
  // An empty bucket has no error rate (null), which the chart draws as a gap, not as 0%.
  const errorRows = toRows(points, { errors: (p) => (p.requests > 0 ? p.errorRate : null) });
  const rt = runtime.data;

  return (
    <>
      <Box sx={{ display: 'flex', gap: 1.5, mb: 2.5, flexWrap: 'wrap', alignItems: 'center' }}>
        <TimeRangePicker value={preset.key} onChange={setPreset} />
        {focus && (
          <Chip
            label={`Charts: ${focus.method} ${focus.route}`}
            onDelete={() => setFocus(null)}
            sx={{ fontFamily: fonts.mono, fontSize: 12 }}
          />
        )}
      </Box>

      {summary.isError && <ErrorNotice error={summary.error} onRetry={() => void summary.refetch()} />}
      {!s ? (
        summary.isPending && <Loading label="Loading performance" />
      ) : (
        <>
          {s.health.status === 'NO_DATA' ? (
            <Alert severity="info" variant="outlined" sx={{ mb: 2.5 }}>
              No requests in this period.{' '}
              {s.connectionStatus !== 'CONNECTED' ? (
                <>
                  This service is not connected yet:{' '}
                  <Link component={RouterLink} to={`/o/${orgId}/services/${serviceId}/connection`}>
                    open the connection guide
                  </Link>
                  .
                </>
              ) : (
                `Last data ${timeAgo(s.lastTelemetryAt)}. Choose a longer period or check that the application is running.`
              )}
            </Alert>
          ) : (
            s.health.reasons.length > 0 &&
            s.health.status !== 'HEALTHY' && (
              <Alert
                severity={s.health.status === 'CRITICAL' ? 'error' : 'warning'}
                variant="outlined"
                sx={{ mb: 2.5 }}
              >
                {s.health.reasons.join(' ')}
              </Alert>
            )
          )}
          <StatRow>
            <StatTile
              label="Health"
              value={<HealthSignal status={s.health.status} />}
              detail={`Last data ${timeAgo(s.lastTelemetryAt)}`}
            />
            <StatTile
              label="Throughput"
              value={fmtRate(s.requestsPerSecond)}
              detail={`${fmtCount(s.requests)} requests`}
            />
            <StatTile
              label="Server errors"
              value={fmtPct(s.errorRate)}
              detail={`${fmtCount(s.serverErrors)} answered 5xx; ${fmtCount(s.clientErrors)} 4xx not counted`}
            />
            <StatTile
              label="p95 latency"
              value={fmtMs(s.p95Ms)}
              detail={`p50 ${fmtMs(s.p50Ms)}, p99 ${fmtMs(s.p99Ms)}, max ${fmtMs(s.maxMs)}`}
            />
          </StatRow>
        </>
      )}

      {series.isError && <ErrorNotice error={series.error} onRetry={() => void series.refetch()} />}
      <ChartGrid>
        <TimeSeriesChart
          title="Latency"
          rows={latencyRows}
          series={[
            { key: 'p50', label: 'p50', color: SERIES[0] },
            { key: 'p95', label: 'p95', color: SERIES[1] },
            { key: 'p99', label: 'p99', color: SERIES[2] },
          ]}
          format={fmtMs}
        />
        <TimeSeriesChart
          title="Throughput"
          rows={throughputRows}
          series={[{ key: 'rps', label: 'Requests', color: SERIES[0] }]}
          format={fmtRate}
        />
        <TimeSeriesChart
          title="Server error rate"
          rows={errorRows}
          series={[{ key: 'errors', label: 'Error rate', color: SERIES[0] }]}
          format={fmtPct}
        />
      </ChartGrid>

      <Panel
        title="Endpoints"
        description="Select an endpoint to focus the charts on it."
        flush
        actions={
          <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
            <TextField
              placeholder="Filter routes"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              sx={{ width: 200 }}
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
            <TextField
              select
              value={sort}
              onChange={(e) => setSort(e.target.value)}
              sx={{ width: 190 }}
              aria-label="Sort endpoints"
            >
              {SORTS.map((o) => (
                <MenuItem key={o.key} value={o.key}>
                  {o.label}
                </MenuItem>
              ))}
            </TextField>
          </Box>
        }
      >
        {endpoints.isError && (
          <Box sx={{ p: 2 }}>
            <ErrorNotice error={endpoints.error} onRetry={() => void endpoints.refetch()} />
          </Box>
        )}
        {!endpoints.data ? (
          endpoints.isPending && (
            <Box sx={{ px: 2.5 }}>
              <Loading label="Loading endpoints" />
            </Box>
          )
        ) : endpoints.data.endpoints.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ p: 2.5 }}>
            {search.trim() ? 'No route matches the filter.' : 'No endpoint received requests in this period.'}
          </Typography>
        ) : (
          <TableContainer>
            <Table>
              <TableHead>
                <TableRow>
                  <TableCell>Endpoint</TableCell>
                  <TableCell align="right">Requests</TableCell>
                  <TableCell align="right">Errors</TableCell>
                  <TableCell align="right">p50</TableCell>
                  <TableCell align="right">p95</TableCell>
                  <TableCell align="right">p99</TableCell>
                  <TableCell>Last seen</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {endpoints.data.endpoints.map((e) => {
                  const selected = focus?.method === e.method && focus.route === e.route;
                  return (
                    <TableRow
                      key={`${e.method} ${e.route}`}
                      hover
                      selected={selected}
                      onClick={() => setFocus(selected ? null : { method: e.method, route: e.route })}
                      sx={{ cursor: 'pointer' }}
                    >
                      <TableCell sx={{ fontFamily: fonts.mono, fontSize: 12.5, wordBreak: 'break-all' }}>
                        <Box component="span" sx={{ fontWeight: 600, color: tokens.ink, mr: 1 }}>
                          {e.method}
                        </Box>
                        {e.route}
                      </TableCell>
                      <TableCell align="right" sx={num}>
                        {fmtCount(e.requests)}
                      </TableCell>
                      <TableCell
                        align="right"
                        sx={{ ...num, color: (e.errorRate ?? 0) >= 0.05 ? tokens.critical : undefined }}
                      >
                        {e.serverErrors > 0 ? `${fmtCount(e.serverErrors)} (${fmtPct(e.errorRate)})` : '0'}
                      </TableCell>
                      <TableCell align="right" sx={num}>
                        {fmtMs(e.p50Ms)}
                      </TableCell>
                      <TableCell align="right" sx={{ ...num, fontWeight: 600 }}>
                        {fmtMs(e.p95Ms)}
                      </TableCell>
                      <TableCell align="right" sx={num}>
                        {fmtMs(e.p99Ms)}
                      </TableCell>
                      <TableCell>{timeAgo(e.lastSeen)}</TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </TableContainer>
        )}
        {endpoints.data && endpoints.data.totalEndpoints > endpoints.data.endpoints.length && (
          <Typography variant="caption" component="p" sx={{ px: 2.5, py: 1.5 }}>
            Showing {endpoints.data.endpoints.length} of {endpoints.data.totalEndpoints} endpoints. Filter by route to
            find others.
          </Typography>
        )}
      </Panel>

      {rt && rt.available && (
        <>
          <Typography variant="h3" sx={{ mb: 1.5 }}>
            Runtime
          </Typography>
          <ChartGrid>
            <TimeSeriesChart
              title="CPU"
              rows={toRows(rt.points, { cpu: (p) => p.cpuUtilization })}
              series={[{ key: 'cpu', label: 'CPU', color: SERIES[0] }]}
              format={fmtPct}
            />
            <TimeSeriesChart
              title="Heap memory"
              rows={toRows(rt.points, { used: (p) => p.heapUsedBytes, limit: (p) => p.heapLimitBytes })}
              series={[
                { key: 'used', label: 'Used', color: SERIES[0] },
                { key: 'limit', label: 'Limit', color: SERIES[1] },
              ]}
              format={fmtBytes}
            />
            <TimeSeriesChart
              title="Threads"
              rows={toRows(rt.points, { threads: (p) => p.threads })}
              series={[{ key: 'threads', label: 'Threads', color: SERIES[0] }]}
              format={(v) => (v === null ? '—' : String(Math.round(v)))}
            />
          </ChartGrid>
        </>
      )}
      {rt && !rt.available && s && s.requests > 0 && (
        <Typography variant="caption" component="p">
          No runtime metrics (CPU, memory, threads) from this service. The OpenTelemetry Java agent exports them by
          default; other SDKs need metrics enabled.
        </Typography>
      )}
      <Box sx={{ mt: 2 }}>
        <Button
          component={RouterLink}
          to={`/o/${orgId}/services/${serviceId}/database${preset.key === '1h' ? '' : `?range=${preset.key}`}`}
        >
          Open database calls
        </Button>
      </Box>
    </>
  );
}
