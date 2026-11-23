package com.pulseops.monitoring.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.pulseops.appservice.service.ServiceCatalogService;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.persistence.LikePatterns;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.monitoring.service.DatabaseViews.Breakdown;
import com.pulseops.monitoring.service.DatabaseViews.CallList;
import com.pulseops.monitoring.service.DatabaseViews.CallView;
import com.pulseops.monitoring.service.DatabaseViews.ConnectionPool;
import com.pulseops.monitoring.service.DatabaseViews.DatabaseSummary;
import com.pulseops.monitoring.service.DatabaseViews.DbSeries;
import com.pulseops.monitoring.service.DatabaseViews.DbSeriesPoint;
import com.pulseops.monitoring.service.DatabaseViews.OperationList;
import com.pulseops.monitoring.service.DatabaseViews.OperationView;
import com.pulseops.monitoring.service.DatabaseViews.PoolPoint;
import com.pulseops.monitoring.service.MonitoringViews.Window;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database monitoring over {@code database_operations} (one row per call, statements already
 * reduced to their shape at ingestion — no parameter values) and connection-pool gauges
 * from OTLP metrics. Scoped by organization explicitly and by row-level security.
 */
@Service
public class DatabaseMonitoringService {

    public static final double DEFAULT_SLOW_MS = 500;
    static final int MAX_OPERATIONS_SCANNED = 2_000;
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    static final List<String> SORTS = List.of("total_time", "calls", "avg", "p95", "p99", "max", "errors",
            "error_rate", "slow");

    private final JdbcTemplate jdbc;
    private final ServiceCatalogService services;
    private final Clock clock;

    public DatabaseMonitoringService(JdbcTemplate jdbc, ServiceCatalogService services, Clock clock) {
        this.jdbc = jdbc;
        this.services = services;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DatabaseSummary summary(OrgAccess access, UUID serviceId, TimeWindow window, Double slowMs) {
        services.get(access, serviceId);
        double slow = slowThreshold(slowMs);
        Object[] range = range(access, window, serviceId);
        DbStats stats = jdbc.queryForObject(MonitoringSql.DB_SUMMARY, (rs, n) -> DbStats.read(rs),
                prepend(slow, range));
        List<Breakdown> breakdown = jdbc.query(MonitoringSql.DB_BREAKDOWN, (rs, n) -> {
            double total = rs.getDouble("total_ms");
            return new Breakdown(rs.getString("db_system"), rs.getString("operation_type"), rs.getLong("calls"),
                    rs.getLong("errors"), LatencyStats.round(total), share(total, stats.totalMs()));
        }, range);
        return new DatabaseSummary(serviceId, Window.of(window), slow, stats.calls(), rate(stats.calls(), window),
                stats.errors(), stats.errorRate(), stats.slowCalls(), stats.totalMs(), stats.avgMs(), stats.p50Ms(),
                stats.p95Ms(), stats.p99Ms(), stats.maxMs(), breakdown,
                HealthRules.assess(HealthRules.DATABASE, stats.calls(), stats.errors(), stats.p95Ms()));
    }

    @Transactional(readOnly = true)
    public OperationList operations(OrgAccess access, UUID serviceId, TimeWindow window, String sort,
                                    String search, Integer limit, Double slowMs) {
        services.get(access, serviceId);
        String order = sort == null || sort.isBlank() ? "total_time" : sort.trim().toLowerCase(Locale.ROOT);
        if (!SORTS.contains(order)) {
            throw ApiException.badRequest("INVALID_SORT", "sort must be one of " + String.join(", ", SORTS));
        }
        double slow = slowThreshold(slowMs);
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        String pattern = LikePatterns.contains(search);
        List<Object> args = new ArrayList<>(List.of(prepend(slow, range(access, window, serviceId))));
        args.add(pattern);
        args.add(pattern);
        args.add(pattern);
        args.add(MAX_OPERATIONS_SCANNED);
        List<OperationView> all = jdbc.query(MonitoringSql.DB_OPERATIONS, (rs, n) -> operation(rs, window),
                args.toArray());
        double totalMs = all.stream().mapToDouble(OperationView::totalMs).sum();
        List<OperationView> withShare = new ArrayList<>(all.size());
        for (OperationView o : all) {
            withShare.add(new OperationView(o.dbSystem(), o.operationType(), o.operation(), o.table(), o.calls(),
                    o.callsPerSecond(), o.errors(), o.errorRate(), o.slowCalls(), o.totalMs(),
                    share(o.totalMs(), totalMs), o.avgMs(), o.p50Ms(), o.p95Ms(), o.p99Ms(), o.maxMs(), o.lastSeen(),
                    o.slowestTraceId()));
        }
        withShare.sort(comparator(order));
        return new OperationList(serviceId, Window.of(window), order, slow, all.size(),
                List.copyOf(withShare.subList(0, Math.min(size, withShare.size()))));
    }

    private OperationView operation(ResultSet rs, TimeWindow window) throws SQLException {
        DbStats s = DbStats.read(rs);
        Timestamp lastSeen = rs.getTimestamp("last_seen");
        return new OperationView(rs.getString("db_system"), rs.getString("operation_type"), rs.getString("operation"),
                rs.getString("table_name"), s.calls(), rate(s.calls(), window), s.errors(), s.errorRate(),
                s.slowCalls(), s.totalMs(), null, s.avgMs(), s.p50Ms(), s.p95Ms(), s.p99Ms(), s.maxMs(),
                lastSeen == null ? null : lastSeen.toInstant(), rs.getString("slowest_trace_id"));
    }

    /** Costliest first for numeric sorts; nulls last; ties broken by total time then text. */
    static Comparator<OperationView> comparator(String sort) {
        Comparator<OperationView> tieBreak = Comparator.comparingDouble(OperationView::totalMs).reversed()
                .thenComparing(o -> o.operation() == null ? "" : o.operation());
        Comparator<OperationView> primary = switch (sort) {
            case "calls" -> Comparator.comparingLong(OperationView::calls).reversed();
            case "avg" -> descending(OperationView::avgMs);
            case "p95" -> descending(OperationView::p95Ms);
            case "p99" -> descending(OperationView::p99Ms);
            case "max" -> descending(OperationView::maxMs);
            case "errors" -> Comparator.comparingLong(OperationView::errors).reversed();
            case "error_rate" -> descending(OperationView::errorRate);
            case "slow" -> Comparator.comparingLong(OperationView::slowCalls).reversed();
            default -> Comparator.comparingDouble(OperationView::totalMs).reversed();
        };
        return primary.thenComparing(tieBreak);
    }

    private static Comparator<OperationView> descending(Function<OperationView, Double> key) {
        return Comparator.comparing(key, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    @Transactional(readOnly = true)
    public DbSeries series(OrgAccess access, UUID serviceId, String operation, TimeWindow window, Double slowMs) {
        services.get(access, serviceId);
        double slow = slowThreshold(slowMs);
        String op = operation == null || operation.isBlank() ? null : operation;
        Map<Instant, DbStats> byBucket = new HashMap<>();
        List<Object> args = new ArrayList<>(List.of(window.stepSeconds(), window.stepSeconds(), slow));
        args.addAll(List.of(range(access, window, serviceId)));
        args.add(op);
        args.add(op);
        jdbc.query(MonitoringSql.DB_SERIES, rs -> {
            byBucket.put(rs.getTimestamp("t").toInstant(), DbStats.read(rs));
        }, args.toArray());
        return new DbSeries(serviceId, op, Window.of(window), slow, fill(window, byBucket));
    }

    static List<DbSeriesPoint> fill(TimeWindow window, Map<Instant, DbStats> byBucket) {
        List<DbSeriesPoint> points = new ArrayList<>();
        for (Instant t : window.points()) {
            DbStats s = byBucket.getOrDefault(t, DbStats.EMPTY);
            points.add(new DbSeriesPoint(t, s.calls(), LatencyStats.round4((double) s.calls() / window.stepSeconds()),
                    s.errors(), s.slowCalls(), s.avgMs(), s.p50Ms(), s.p95Ms(), s.p99Ms()));
        }
        return points;
    }

    /** Individual calls at or above a duration (default: the slow threshold), slowest first. */
    @Transactional(readOnly = true)
    public CallList calls(OrgAccess access, UUID serviceId, TimeWindow window, Double minDurationMs,
                          boolean errorsOnly, Integer limit) {
        services.get(access, serviceId);
        double min = minDurationMs == null ? (errorsOnly ? 0 : DEFAULT_SLOW_MS) : minDurationMs;
        if (min < 0 || min > 3_600_000) {
            throw ApiException.badRequest("INVALID_DURATION", "minDurationMs must be between 0 and 3600000");
        }
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        List<Object> args = new ArrayList<>(List.of(range(access, window, serviceId)));
        args.add(min);
        args.add(errorsOnly);
        args.add(size);
        List<CallView> calls = jdbc.query(MonitoringSql.DB_CALLS, (rs, n) -> new CallView(
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("db_system"), rs.getString("operation_type"),
                rs.getString("operation"), rs.getString("table_name"), LatencyStats.round(rs.getDouble("duration_ms")),
                rs.getBoolean("is_error"), rs.getString("error_message"), rs.getString("trace_id"),
                rs.getString("span_id")), args.toArray());
        return new CallList(serviceId, Window.of(window), min, errorsOnly, calls);
    }

    @Transactional(readOnly = true)
    public ConnectionPool pool(OrgAccess access, UUID serviceId, TimeWindow window) {
        services.get(access, serviceId);
        Object[] range = {access.organizationId(), Timestamp.from(window.from()), Timestamp.from(window.to()),
                serviceId};
        Map<Instant, Map<String, double[]>> byTime = new HashMap<>();
        List<Object> args = new ArrayList<>(List.of(range));
        args.add(window.stepSeconds());
        args.add(window.stepSeconds());
        jdbc.query(MonitoringSql.DB_POOL, rs -> {
            byTime.computeIfAbsent(rs.getTimestamp("t").toInstant(), t -> new HashMap<>())
                    .put(rs.getString("k"), new double[]{rs.getDouble("v"), rs.getDouble("peak")});
        }, args.toArray());
        List<String> pools = jdbc.queryForList(MonitoringSql.DB_POOL_NAMES, String.class, range);
        return new ConnectionPool(serviceId, Window.of(window), !byTime.isEmpty(),
                pools.stream().filter(p -> p != null).toList(), poolPoints(window, byTime));
    }

    static List<PoolPoint> poolPoints(TimeWindow window, Map<Instant, Map<String, double[]>> byTime) {
        List<PoolPoint> points = new ArrayList<>();
        for (Instant t : window.points()) {
            Map<String, double[]> v = byTime.getOrDefault(t, Map.of());
            double[] used = v.get("used");
            points.add(new PoolPoint(t, avg(used), avg(v.get("idle")), avg(v.get("max")), avg(v.get("pending")),
                    used == null ? null : LatencyStats.round(used[1])));
        }
        return points;
    }

    private static Double avg(double[] values) {
        return values == null ? null : LatencyStats.round(values[0]);
    }

    // ------------------------------------------------------------------------------ helpers

    static double slowThreshold(Double slowMs) {
        if (slowMs == null) {
            return DEFAULT_SLOW_MS;
        }
        if (slowMs < 1 || slowMs > 600_000) {
            throw ApiException.badRequest("INVALID_THRESHOLD", "slowMs must be between 1 and 600000");
        }
        return slowMs;
    }

    static Double share(double part, double total) {
        return total <= 0 ? null : LatencyStats.round4(part / total);
    }

    private Double rate(long calls, TimeWindow window) {
        return calls == 0 ? 0.0 : LatencyStats.round4(calls / window.elapsedSeconds(clock));
    }

    private static Object[] range(OrgAccess access, TimeWindow window, UUID serviceId) {
        return new Object[]{access.organizationId(), Timestamp.from(window.from()), Timestamp.from(window.to()),
                serviceId};
    }

    private static Object[] prepend(Object first, Object[] rest) {
        Object[] all = new Object[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }
}
