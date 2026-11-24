import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Chip,
  FormControlLabel,
  InputAdornment,
  MenuItem,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import SearchIcon from '@mui/icons-material/Search';
import { databaseApi } from '../../api/endpoints';
import type { DbOperation } from '../../api/types';
import { ChartGrid, TimeSeriesChart, toRows } from '../../components/charts/TimeSeriesChart';
import { SERIES } from '../../components/charts/chartTheme';
import { ErrorNotice, Loading } from '../../components/Feedback';
import { HealthSignal } from '../../components/HealthSignal';
import { Panel } from '../../components/Panel';
import { StatRow, StatTile } from '../../components/StatTile';
import { TimeRangePicker } from '../../components/TimeRangePicker';
import { useOrg } from '../../layout/OrgContext';
import { formatDateTime, timeAgo } from '../../lib/format';
import { fmtCount, fmtMs, fmtPct, fmtRate } from '../../lib/numbers';
import { useTimeRange } from '../../lib/timeRange';
import { fonts, tokens } from '../../theme';

const SORTS = [
  { key: 'total_time', label: 'Most total time' },
  { key: 'p95', label: 'Slowest (p95)' },
  { key: 'calls', label: 'Most calls' },
  { key: 'slow', label: 'Most slow calls' },
  { key: 'errors', label: 'Most errors' },
];

const num = { fontVariantNumeric: 'tabular-nums' } as const;

/** Share of database time as a thin meter: the one place a bar beats a number in a table. */
function Share({ value }: { value: number | null }) {
  const pct = Math.max(0, Math.min(1, value ?? 0));
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, justifyContent: 'flex-end' }}>
      <Box sx={{ width: 64, height: 6, borderRadius: 3, bgcolor: tokens.cobaltSoft, overflow: 'hidden' }} aria-hidden>
        <Box sx={{ width: `${pct * 100}%`, height: '100%', bgcolor: tokens.cobalt, borderRadius: 3 }} />
      </Box>
      <Box component="span" sx={{ ...num, minWidth: 40, textAlign: 'right' }}>
        {fmtPct(value)}
      </Box>
    </Box>
  );
}

function Statement({ op }: { op: DbOperation }) {
  return (
    <Tooltip
      title={<Box sx={{ fontFamily: fonts.mono, fontSize: 12, whiteSpace: 'pre-wrap' }}>{op.operation}</Box>}
      placement="bottom-start"
    >
      <Box
        sx={{
          fontFamily: fonts.mono,
          fontSize: 12.5,
          color: tokens.ink,
          maxWidth: 520,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}
      >
        {op.operation}
      </Box>
    </Tooltip>
  );
}

export function ServiceDatabaseTab() {
  const { serviceId = '' } = useParams();
  const { organization } = useOrg();
  const orgId = organization.id;
  const { preset, setPreset, range, refetchInterval } = useTimeRange();
  const [sort, setSort] = useState('total_time');
  const [search, setSearch] = useState('');
  const [focus, setFocus] = useState<string | null>(null);
  const [errorsOnly, setErrorsOnly] = useState(false);

  const summary = useQuery({
    queryKey: ['db-summary', orgId, serviceId, preset.key],
    queryFn: () => databaseApi.summary(orgId, serviceId, range()),
    refetchInterval,
    placeholderData: keepPreviousData,
  });
  const series = useQuery({
    queryKey: ['db-series', orgId, serviceId, preset.key, focus],
    queryFn: () => databaseApi.timeseries(orgId, serviceId, range(), focus ?? undefined),
    refetchInterval,
    placeholderData: keepPreviousData,
  });
  const operations = useQuery({
    queryKey: ['db-ops', orgId, serviceId, preset.key, sort, search.trim()],
    queryFn: () => databaseApi.operations(orgId, serviceId, range(), sort, search.trim() || undefined),
    refetchInterval,
    placeholderData: keepPreviousData,
  });
  const calls = useQuery({
    queryKey: ['db-calls', orgId, serviceId, preset.key, errorsOnly],
    queryFn: () => databaseApi.calls(orgId, serviceId, range(), errorsOnly),
    refetchInterval,
    placeholderData: keepPreviousData,
  });
  const pool = useQuery({
    queryKey: ['db-pool', orgId, serviceId, preset.key],
    queryFn: () => databaseApi.pool(orgId, serviceId, range()),
    refetchInterval,
    placeholderData: keepPreviousData,
  });

  const s = summary.data;
  const points = series.data?.points ?? [];
  const slowMs = s?.slowThresholdMs ?? 500;

  return (
    <>
      <Box sx={{ display: 'flex', gap: 1.5, mb: 2.5, flexWrap: 'wrap', alignItems: 'center' }}>
        <TimeRangePicker value={preset.key} onChange={setPreset} />
        {focus && (
          <Chip
            label={`Charts: ${focus.length > 60 ? focus.slice(0, 57) + '…' : focus}`}
            onDelete={() => setFocus(null)}
            sx={{ fontFamily: fonts.mono, fontSize: 12, maxWidth: 520 }}
          />
        )}
      </Box>

      {summary.isError && <ErrorNotice error={summary.error} onRetry={() => void summary.refetch()} />}
      {!s ? (
        summary.isPending && <Loading label="Loading database calls" />
      ) : s.calls === 0 ? (
        <Alert severity="info" variant="outlined" sx={{ mb: 2.5 }}>
          No database calls recorded in this period. Calls appear when the service is instrumented (the Java agent
          records JDBC calls automatically) and talks to a database.
        </Alert>
      ) : (
        <>
          {s.health.status !== 'HEALTHY' && s.health.reasons.length > 0 && (
            <Alert severity={s.health.status === 'CRITICAL' ? 'error' : 'warning'} variant="outlined" sx={{ mb: 2.5 }}>
              {s.health.reasons.join(' ')}
            </Alert>
          )}
          <StatRow>
            <StatTile
              label="Database health"
              value={<HealthSignal status={s.health.status} />}
              detail="Thresholds: p95 200 ms / 1 s, errors 1% / 5%"
            />
            <StatTile
              label="Calls"
              value={fmtRate(s.callsPerSecond, 'calls')}
              detail={`${fmtCount(s.calls)} in ${preset.label.toLowerCase()}`}
            />
            <StatTile
              label="p95 duration"
              value={fmtMs(s.p95Ms)}
              detail={`p50 ${fmtMs(s.p50Ms)}, p99 ${fmtMs(s.p99Ms)}, max ${fmtMs(s.maxMs)}`}
            />
            <StatTile label="Slow calls" value={fmtCount(s.slowCalls)} detail={`Over ${fmtMs(slowMs)}`} />
            <StatTile label="Failed calls" value={fmtPct(s.errorRate)} detail={`${fmtCount(s.errors)} errors`} />
          </StatRow>
        </>
      )}

      {series.isError && <ErrorNotice error={series.error} onRetry={() => void series.refetch()} />}
      <ChartGrid>
        <TimeSeriesChart
          title="Call duration"
          rows={toRows(points, { p50: (p) => p.p50Ms, p95: (p) => p.p95Ms, p99: (p) => p.p99Ms })}
          series={[
            { key: 'p50', label: 'p50', color: SERIES[0] },
            { key: 'p95', label: 'p95', color: SERIES[1] },
            { key: 'p99', label: 'p99', color: SERIES[2] },
          ]}
          format={fmtMs}
        />
        <TimeSeriesChart
          title="Calls"
          rows={toRows(points, { cps: (p) => p.callsPerSecond })}
          series={[{ key: 'cps', label: 'Calls', color: SERIES[0] }]}
          format={(v) => fmtRate(v, 'calls')}
        />
        {pool.data?.available && (
          <TimeSeriesChart
            title="Connection pool"
            rows={toRows(pool.data.points, { used: (p) => p.used, max: (p) => p.max })}
            series={[
              { key: 'used', label: 'In use', color: SERIES[0] },
              { key: 'max', label: 'Maximum', color: SERIES[1] },
            ]}
            format={(v) => (v === null ? '—' : String(Math.round(v)))}
          />
        )}
      </ChartGrid>

      <Panel
        title="Statements"
        description="Each statement shape with literals replaced by ?. Values are never collected. Select one to focus the charts."
        flush
        actions={
          <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
            <TextField
              placeholder="Filter statements or tables"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              sx={{ width: 230 }}
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
              aria-label="Sort statements"
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
        {operations.isError && (
          <Box sx={{ p: 2 }}>
            <ErrorNotice error={operations.error} onRetry={() => void operations.refetch()} />
          </Box>
        )}
        {!operations.data ? (
          operations.isPending && (
            <Box sx={{ px: 2.5 }}>
              <Loading label="Loading statements" />
            </Box>
          )
        ) : operations.data.operations.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ p: 2.5 }}>
            {search.trim() ? 'No statement matches the filter.' : 'No statements in this period.'}
          </Typography>
        ) : (
          <TableContainer>
            <Table>
              <TableHead>
                <TableRow>
                  <TableCell>Statement</TableCell>
                  <TableCell align="right">Share of time</TableCell>
                  <TableCell align="right">Calls</TableCell>
                  <TableCell align="right">p95</TableCell>
                  <TableCell align="right">Slow</TableCell>
                  <TableCell align="right">Errors</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {operations.data.operations.map((op) => {
                  const selected = focus === op.operation;
                  return (
                    <TableRow
                      key={op.operation}
                      hover
                      selected={selected}
                      onClick={() => setFocus(selected ? null : op.operation)}
                      sx={{ cursor: 'pointer' }}
                    >
                      <TableCell sx={{ maxWidth: 560 }}>
                        <Statement op={op} />
                        <Typography variant="caption">
                          {[op.dbSystem, op.operationType, op.table].filter(Boolean).join(', ')}
                          {op.lastSeen ? `, last ${timeAgo(op.lastSeen)}` : ''}
                        </Typography>
                      </TableCell>
                      <TableCell align="right">
                        <Share value={op.shareOfTime} />
                      </TableCell>
                      <TableCell align="right" sx={num}>
                        {fmtCount(op.calls)}
                      </TableCell>
                      <TableCell align="right" sx={{ ...num, fontWeight: 600 }}>
                        {fmtMs(op.p95Ms)}
                      </TableCell>
                      <TableCell align="right" sx={num}>
                        {fmtCount(op.slowCalls)}
                      </TableCell>
                      <TableCell align="right" sx={{ ...num, color: op.errors > 0 ? tokens.critical : undefined }}>
                        {fmtCount(op.errors)}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Panel>

      <Panel
        title={errorsOnly ? 'Failed calls' : 'Slow calls'}
        description={
          errorsOnly ? 'Most recent failures first.' : `Individual calls over ${fmtMs(slowMs)}, slowest first.`
        }
        flush
        actions={
          <FormControlLabel
            control={<Switch size="small" checked={errorsOnly} onChange={(e) => setErrorsOnly(e.target.checked)} />}
            label={<Typography variant="body2">Failed calls only</Typography>}
          />
        }
      >
        {calls.isError && (
          <Box sx={{ p: 2 }}>
            <ErrorNotice error={calls.error} onRetry={() => void calls.refetch()} />
          </Box>
        )}
        {calls.data && calls.data.calls.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ p: 2.5 }}>
            {errorsOnly ? 'No failed calls in this period.' : 'No slow calls in this period.'}
          </Typography>
        ) : (
          calls.data && (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Time</TableCell>
                    <TableCell align="right">Duration</TableCell>
                    <TableCell>Statement</TableCell>
                    <TableCell>Trace</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {calls.data.calls.map((c) => (
                    <TableRow key={`${c.occurredAt}-${c.spanId ?? c.operation}`}>
                      <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(c.occurredAt)}</TableCell>
                      <TableCell align="right" sx={{ ...num, fontWeight: 600 }}>
                        {fmtMs(c.durationMs)}
                      </TableCell>
                      <TableCell sx={{ maxWidth: 520 }}>
                        <Box
                          sx={{
                            fontFamily: fonts.mono,
                            fontSize: 12,
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
                          }}
                        >
                          {c.operation}
                        </Box>
                        {c.error && c.errorMessage && (
                          <Typography variant="caption" sx={{ color: tokens.critical }}>
                            {c.errorMessage}
                          </Typography>
                        )}
                      </TableCell>
                      <TableCell sx={{ fontFamily: fonts.mono, fontSize: 12 }}>
                        {c.traceId ? c.traceId.slice(0, 12) + '…' : '—'}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )
        )}
      </Panel>
    </>
  );
}
