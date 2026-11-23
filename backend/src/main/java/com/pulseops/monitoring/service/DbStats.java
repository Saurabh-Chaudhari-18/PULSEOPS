package com.pulseops.monitoring.service;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Statistics over individual database calls. Percentiles are exact (computed by PostgreSQL
 * over the raw durations). Duration fields are null when there were no calls.
 */
public record DbStats(long calls, long errors, long slowCalls, double totalMs, Double avgMs, Double p50Ms,
                      Double p95Ms, Double p99Ms, Double maxMs) {

    public static final DbStats EMPTY = new DbStats(0, 0, 0, 0, null, null, null, null, null);

    /** Reads the columns of {@link MonitoringSql#DB_STATS_COLUMNS}. */
    static DbStats read(ResultSet rs) throws SQLException {
        long calls = rs.getLong("calls");
        if (calls == 0) {
            return EMPTY;
        }
        return new DbStats(calls, rs.getLong("errors"), rs.getLong("slow_calls"),
                LatencyStats.round(rs.getDouble("total_ms")), nullable(rs, "avg_ms"), nullable(rs, "p50_ms"),
                nullable(rs, "p95_ms"), nullable(rs, "p99_ms"), nullable(rs, "max_ms"));
    }

    private static Double nullable(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : LatencyStats.round(v);
    }

    public Double errorRate() {
        return calls == 0 ? null : LatencyStats.round4((double) errors / calls);
    }
}
