import { useState, type ReactNode } from 'react';
import {
  Box,
  Link,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { tokens } from '../../theme';
import { chartInk } from './chartTheme';

export interface SeriesDef {
  key: string;
  label: string;
  color: string;
}

export type ChartRow = { t: number } & Record<string, number | null>;

interface TooltipItem {
  dataKey?: unknown;
  value?: unknown;
  color?: string;
}

function timeLabel(t: number, spanMs: number): string {
  const d = new Date(t);
  if (spanMs > 36 * 3600_000) {
    return d.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  }
  return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
}

/**
 * A line chart for one measure over time: one y-axis, 2px lines, gaps where data is absent
 * (null is "no data", never zero), a crosshair tooltip listing every series, a legend when
 * there are two or more series, and a table view of the same numbers.
 */
export function TimeSeriesChart({
  title,
  rows,
  series,
  format,
  height = 190,
  emptyText = 'No data in this period.',
}: {
  title: string;
  rows: ChartRow[];
  series: SeriesDef[];
  format: (value: number | null) => string;
  height?: number;
  emptyText?: string;
}) {
  const [asTable, setAsTable] = useState(false);
  const spanMs = rows.length > 1 ? rows[rows.length - 1].t - rows[0].t : 0;
  const hasData = rows.some((r) => series.some((s) => r[s.key] !== null && r[s.key] !== undefined));

  const renderTooltip = ({
    active,
    payload,
    label,
  }: {
    active?: boolean;
    payload?: readonly TooltipItem[];
    label?: unknown;
  }) => {
    if (!active || !payload || payload.length === 0) return null;
    return (
      <Box
        sx={{
          bgcolor: 'background.paper',
          border: 1,
          borderColor: 'divider',
          borderRadius: 1,
          px: 1.5,
          py: 1,
          minWidth: 150,
        }}
      >
        <Typography variant="caption" component="div" sx={{ mb: 0.5 }}>
          {timeLabel(Number(label), spanMs)}
        </Typography>
        {series.map((s) => {
          const item = payload.find((p) => p.dataKey === s.key);
          const value = item?.value === null || item?.value === undefined ? null : Number(item.value);
          return (
            <Box key={s.key} sx={{ display: 'flex', alignItems: 'center', gap: 1, fontSize: 13 }}>
              <Box sx={{ width: 12, height: 2, bgcolor: s.color, borderRadius: 1, flexShrink: 0 }} />
              <Box component="span" sx={{ fontWeight: 600, color: tokens.ink, fontVariantNumeric: 'tabular-nums' }}>
                {format(value)}
              </Box>
              <Box component="span" sx={{ color: tokens.muted }}>
                {s.label}
              </Box>
            </Box>
          );
        })}
      </Box>
    );
  };

  return (
    <Paper variant="outlined" sx={{ p: 2, minWidth: 0 }}>
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 2, mb: 1, flexWrap: 'wrap' }}>
        <Typography variant="h4" sx={{ flex: 1 }}>
          {title}
        </Typography>
        {series.length > 1 && (
          <Box sx={{ display: 'flex', gap: 1.5 }} aria-label="Legend">
            {series.map((s) => (
              <Box key={s.key} sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
                <Box sx={{ width: 14, height: 2, bgcolor: s.color, borderRadius: 1 }} />
                <Typography variant="caption">{s.label}</Typography>
              </Box>
            ))}
          </Box>
        )}
        {hasData && (
          <Link component="button" variant="caption" onClick={() => setAsTable((v) => !v)}>
            {asTable ? 'Show chart' : 'Show table'}
          </Link>
        )}
      </Box>
      {!hasData ? (
        <Box sx={{ height, display: 'grid', placeItems: 'center' }}>
          <Typography variant="body2" color="text.secondary">
            {emptyText}
          </Typography>
        </Box>
      ) : asTable ? (
        <TableContainer sx={{ maxHeight: height + 40 }}>
          <Table size="small" stickyHeader>
            <TableHead>
              <TableRow>
                <TableCell>Time</TableCell>
                {series.map((s) => (
                  <TableCell key={s.key} align="right">
                    {s.label}
                  </TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((r) => (
                <TableRow key={r.t}>
                  <TableCell>{timeLabel(r.t, spanMs)}</TableCell>
                  {series.map((s) => (
                    <TableCell key={s.key} align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                      {format(r[s.key] ?? null)}
                    </TableCell>
                  ))}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      ) : (
        <Box sx={{ height }} role="img" aria-label={`${title} over time`}>
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={rows} margin={{ top: 6, right: 8, bottom: 0, left: 0 }}>
              <CartesianGrid stroke={chartInk.grid} vertical={false} />
              <XAxis
                dataKey="t"
                type="number"
                scale="time"
                domain={['dataMin', 'dataMax']}
                tickFormatter={(t: number) => timeLabel(t, spanMs)}
                tick={{ fontSize: 11, fill: chartInk.tick }}
                axisLine={{ stroke: chartInk.axis }}
                tickLine={false}
                minTickGap={40}
              />
              <YAxis
                tickFormatter={(v: number) => format(v)}
                tick={{ fontSize: 11, fill: chartInk.tick }}
                axisLine={false}
                tickLine={false}
                width={64}
                domain={[0, 'auto']}
                allowDecimals
              />
              <Tooltip
                content={renderTooltip}
                cursor={{ stroke: chartInk.axis, strokeWidth: 1 }}
                isAnimationActive={false}
              />
              {series.map((s) => (
                <Line
                  key={s.key}
                  dataKey={s.key}
                  name={s.label}
                  stroke={s.color}
                  strokeWidth={2}
                  strokeLinejoin="round"
                  strokeLinecap="round"
                  dot={false}
                  activeDot={{ r: 4, stroke: '#FFFFFF', strokeWidth: 2 }}
                  connectNulls={false}
                  isAnimationActive={false}
                  type="linear"
                />
              ))}
            </LineChart>
          </ResponsiveContainer>
        </Box>
      )}
    </Paper>
  );
}

/** Convert API points to chart rows: time as epoch ms, the chosen fields, null kept as null. */
export function toRows<T extends { time: string }>(
  points: T[],
  fields: Partial<Record<string, (p: T) => number | null>>,
): ChartRow[] {
  return points.map((p) => {
    const row: ChartRow = { t: new Date(p.time).getTime() };
    for (const [key, pick] of Object.entries(fields)) {
      if (pick) row[key] = pick(p);
    }
    return row;
  });
}

export function ChartGrid({ children, min = 320 }: { children: ReactNode; min?: number }) {
  return (
    <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: `repeat(auto-fit, minmax(${min}px, 1fr))`, mb: 3 }}>
      {children}
    </Box>
  );
}
