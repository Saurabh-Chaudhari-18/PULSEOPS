package com.pulseops.monitoring.service;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.pulseops.processing.service.LatencyHistogram;

/**
 * Request statistics for one group of api_metrics rows (a service, an endpoint or a time
 * bucket). Percentiles come from the merged histogram (ADR-014) — never averaged.
 * Latency fields are null when there were no requests: absent data is not zero.
 */
public record LatencyStats(long requests, long serverErrors, long clientErrors, Double avgMs, Double p50Ms,
                           Double p95Ms, Double p99Ms, Double maxMs) {

    public static final LatencyStats EMPTY = new LatencyStats(0, 0, 0, null, null, null, null, null);

    public static LatencyStats of(long requests, long serverErrors, long clientErrors, double durationSumMs,
                                  Double maxMs, long[] buckets) {
        if (requests == 0) {
            return new LatencyStats(0, 0, 0, null, null, null, null, null);
        }
        LatencyHistogram h = LatencyHistogram.of(buckets);
        Double avg = round(durationSumMs / requests);
        return new LatencyStats(requests, serverErrors, clientErrors, avg, round(h.percentile(0.50, maxMs)),
                round(h.percentile(0.95, maxMs)), round(h.percentile(0.99, maxMs)), round(maxMs));
    }

    /**
     * Reads the columns {@code requests, server_errors, client_errors, duration_sum, duration_max, buckets}
     * produced by {@link MonitoringSql#STATS_COLUMNS}.
     */
    static LatencyStats read(ResultSet rs) throws SQLException {
        double max = rs.getDouble("duration_max");
        Double maxMs = rs.wasNull() ? null : max;
        return of(rs.getLong("requests"), rs.getLong("server_errors"), rs.getLong("client_errors"),
                rs.getDouble("duration_sum"), maxMs, buckets(rs.getArray("buckets")));
    }

    static long[] buckets(Array array) throws SQLException {
        if (array == null) {
            return new long[0];
        }
        Object[] values = (Object[]) array.getArray();
        long[] out = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i] == null ? 0 : ((Number) values[i]).longValue();
        }
        return out;
    }

    /** Share of requests that failed with a 5xx, 0..1; null without requests. */
    public Double errorRate() {
        return requests == 0 ? null : round4((double) serverErrors / requests);
    }

    static Double round(Double v) {
        return v == null ? null : Math.round(v * 100.0) / 100.0;
    }

    static Double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
