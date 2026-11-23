package com.pulseops.monitoring.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.pulseops.appservice.service.ServiceCatalogService;
import com.pulseops.appservice.service.ServiceView;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.persistence.LikePatterns;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.monitoring.service.MonitoringViews.DatabaseTotals;
import com.pulseops.monitoring.service.MonitoringViews.EndpointList;
import com.pulseops.monitoring.service.MonitoringViews.EndpointView;
import com.pulseops.monitoring.service.MonitoringViews.Health;
import com.pulseops.monitoring.service.MonitoringViews.OrganizationOverview;
import com.pulseops.monitoring.service.MonitoringViews.SeriesPoint;
import com.pulseops.monitoring.service.MonitoringViews.ServiceOverview;
import com.pulseops.monitoring.service.MonitoringViews.ServiceSummary;
import com.pulseops.monitoring.service.MonitoringViews.TimeSeries;
import com.pulseops.monitoring.service.MonitoringViews.Window;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application and API monitoring read model over {@code api_metrics} (one-minute,
 * per route/method/status-class rows with mergeable latency histograms). Every query is
 * scoped by organization explicitly and by row-level security.
 */
@Service
public class ApiMonitoringService {

    /** Upper bound on distinct endpoints read for one service (route cardinality guard). */
    static final int MAX_ENDPOINTS_SCANNED = 2_000;
    public static final int DEFAULT_ENDPOINT_LIMIT = 50;
    public static final int MAX_ENDPOINT_LIMIT = 200;
    static final List<String> SORTS = List.of("requests", "errors", "error_rate", "p95", "p99", "avg", "route");

    private final JdbcTemplate jdbc;
    private final ServiceCatalogService services;
    private final Clock clock;

    public ApiMonitoringService(JdbcTemplate jdbc, ServiceCatalogService services, Clock clock) {
        this.jdbc = jdbc;
        this.services = services;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------ service

    @Transactional(readOnly = true)
    public ServiceSummary summary(OrgAccess access, UUID serviceId, TimeWindow window) {
        ServiceView service = services.get(access, serviceId);
        LatencyStats stats = jdbc.queryForObject(MonitoringSql.SERVICE_STATS, (rs, n) -> LatencyStats.read(rs),
                access.organizationId(), ts(window.from()), ts(window.to()), serviceId);
        Map<String, Long> statusClasses = new LinkedHashMap<>();
        for (String c : List.of("1xx", "2xx", "3xx", "4xx", "5xx")) {
            statusClasses.put(c, 0L);
        }
        jdbc.query(MonitoringSql.SERVICE_STATUS_CLASSES,
                rs -> {
                    statusClasses.put(rs.getString("status_class"), rs.getLong("n"));
                },
                access.organizationId(), ts(window.from()), ts(window.to()), serviceId);
        return new ServiceSummary(service.id(), service.name(), connectionStatus(service),
                service.connection() == null ? null : service.connection().lastTelemetryAt(),
                Window.of(window), stats.requests(), rate(stats.requests(), window), stats.serverErrors(),
                stats.clientErrors(), stats.errorRate(), stats.avgMs(), stats.p50Ms(), stats.p95Ms(), stats.p99Ms(),
                stats.maxMs(), statusClasses, HealthRules.assess(stats));
    }

    /** Request/error/latency series for a service, or one endpoint when method and route are given. */
    @Transactional(readOnly = true)
    public TimeSeries timeSeries(OrgAccess access, UUID serviceId, String method, String route, TimeWindow window) {
        services.get(access, serviceId);
        boolean endpoint = route != null && !route.isBlank();
        String normalizedMethod = endpoint ? normalizeMethod(method) : null;
        Map<Instant, LatencyStats> byBucket = new HashMap<>();
        jdbc.query(MonitoringSql.SERIES,
                rs -> {
                    byBucket.put(rs.getTimestamp("t").toInstant(), LatencyStats.read(rs));
                },
                window.stepSeconds(), window.stepSeconds(), access.organizationId(), ts(window.from()),
                ts(window.to()), serviceId, endpoint ? route : null, endpoint ? route : null, normalizedMethod,
                normalizedMethod);
        return new TimeSeries(serviceId, normalizedMethod, endpoint ? route : null, Window.of(window),
                fill(window, byBucket));
    }

    /** Every point of the window; a bucket without rows reports 0 requests and null latency. */
    static List<SeriesPoint> fill(TimeWindow window, Map<Instant, LatencyStats> byBucket) {
        List<SeriesPoint> points = new ArrayList<>();
        for (Instant t : window.points()) {
            LatencyStats s = byBucket.getOrDefault(t, LatencyStats.EMPTY);
            points.add(new SeriesPoint(t, s.requests(),
                    LatencyStats.round4((double) s.requests() / window.stepSeconds()), s.serverErrors(),
                    s.clientErrors(), s.errorRate(), s.avgMs(), s.p50Ms(), s.p95Ms(), s.p99Ms()));
        }
        return points;
    }

    // ---------------------------------------------------------------------------- endpoints

    @Transactional(readOnly = true)
    public EndpointList endpoints(OrgAccess access, UUID serviceId, TimeWindow window, String sort, String search,
                                  Integer limit) {
        services.get(access, serviceId);
        String order = sort == null || sort.isBlank() ? "requests" : sort.trim().toLowerCase(Locale.ROOT);
        if (!SORTS.contains(order)) {
            throw ApiException.badRequest("INVALID_SORT", "sort must be one of " + String.join(", ", SORTS));
        }
        int size = limit == null ? DEFAULT_ENDPOINT_LIMIT : Math.max(1, Math.min(limit, MAX_ENDPOINT_LIMIT));
        String pattern = LikePatterns.contains(search);
        List<EndpointView> all = jdbc.query(MonitoringSql.ENDPOINTS,
                (rs, n) -> endpoint(rs, window),
                access.organizationId(), ts(window.from()), ts(window.to()), serviceId, pattern, pattern,
                MAX_ENDPOINTS_SCANNED);
        List<EndpointView> sorted = new ArrayList<>(all);
        sorted.sort(comparator(order));
        return new EndpointList(serviceId, Window.of(window), order, all.size(),
                List.copyOf(sorted.subList(0, Math.min(size, sorted.size()))));
    }

    private EndpointView endpoint(ResultSet rs, TimeWindow window) throws SQLException {
        LatencyStats s = LatencyStats.read(rs);
        Timestamp lastSeen = rs.getTimestamp("last_seen");
        return new EndpointView(rs.getString("http_method"), rs.getString("route_template"), s.requests(),
                rate(s.requests(), window), s.serverErrors(), s.clientErrors(), s.errorRate(), s.avgMs(), s.p50Ms(),
                s.p95Ms(), s.p99Ms(), s.maxMs(), lastSeen == null ? null : lastSeen.toInstant());
    }

    /** Worst first for numeric sorts; nulls last; ties broken by traffic then route. */
    static Comparator<EndpointView> comparator(String sort) {
        Comparator<EndpointView> tieBreak = Comparator.comparingLong(EndpointView::requests).reversed()
                .thenComparing(EndpointView::route).thenComparing(EndpointView::method);
        Comparator<EndpointView> primary = switch (sort) {
            case "errors" -> Comparator.comparingLong(EndpointView::serverErrors).reversed();
            case "error_rate" -> descending(EndpointView::errorRate);
            case "p95" -> descending(EndpointView::p95Ms);
            case "p99" -> descending(EndpointView::p99Ms);
            case "avg" -> descending(EndpointView::avgMs);
            case "route" -> Comparator.comparing(EndpointView::route).thenComparing(EndpointView::method);
            default -> Comparator.comparingLong(EndpointView::requests).reversed();
        };
        return primary.thenComparing(tieBreak);
    }

    private static Comparator<EndpointView> descending(Function<EndpointView, Double> key) {
        return Comparator.comparing(key, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    // ----------------------------------------------------------------------------- overview

    /** "What needs my attention?": every live service with its health, worst first. */
    @Transactional(readOnly = true)
    public OrganizationOverview overview(OrgAccess access, TimeWindow window, UUID projectId) {
        List<ServiceOverview> rows = jdbc.query(MonitoringSql.OVERVIEW,
                (rs, n) -> {
                    LatencyStats s = LatencyStats.read(rs);
                    Timestamp last = rs.getTimestamp("last_telemetry_at");
                    String connection = rs.getString("connection_status");
                    return new ServiceOverview(rs.getObject("id", UUID.class), rs.getString("name"),
                            rs.getObject("project_id", UUID.class), rs.getString("project_name"),
                            connection == null ? "NOT_CONFIGURED" : connection.toUpperCase(Locale.ROOT),
                            last == null ? null : last.toInstant(), s.requests(), rate(s.requests(), window),
                            s.errorRate(), s.p95Ms(), HealthRules.assess(s));
                },
                access.organizationId(), ts(window.from()), ts(window.to()), access.organizationId(), projectId,
                projectId);

        Map<Health, Long> byHealth = new EnumMap<>(Health.class);
        for (Health h : Health.values()) {
            byHealth.put(h, 0L);
        }
        int connected = 0;
        for (ServiceOverview row : rows) {
            byHealth.merge(row.health().status(), 1L, Long::sum);
            if ("CONNECTED".equals(row.connectionStatus())) {
                connected++;
            }
        }
        // Org-wide p95 needs the merged histogram of all services, not an average of p95s.
        LatencyStats total = jdbc.queryForObject(MonitoringSql.ORGANIZATION_STATS,
                (rs, n) -> LatencyStats.read(rs),
                access.organizationId(), ts(window.from()), ts(window.to()), projectId, projectId);

        List<ServiceOverview> all = rows.stream()
                .sorted(Comparator.comparingInt((ServiceOverview v) -> severity(v.health().status()))
                        .thenComparing(Comparator.comparingLong(ServiceOverview::requests).reversed())
                        .thenComparing(ServiceOverview::serviceName))
                .toList();
        List<ServiceOverview> attention = all.stream()
                .filter(v -> v.health().status() == Health.CRITICAL || v.health().status() == Health.WARNING
                        || "ERROR".equals(v.connectionStatus()))
                .toList();
        DbStats db = jdbc.queryForObject(MonitoringSql.DB_ORGANIZATION_STATS, (rs, n) -> DbStats.read(rs),
                DatabaseMonitoringService.DEFAULT_SLOW_MS, access.organizationId(), ts(window.from()),
                ts(window.to()), projectId, projectId);
        DatabaseTotals database = new DatabaseTotals(db.calls(), db.errors(), db.errorRate(), db.slowCalls(),
                db.p95Ms(), HealthRules.assess(HealthRules.DATABASE, db.calls(), db.errors(), db.p95Ms()));
        return new OrganizationOverview(Window.of(window), rows.size(), connected, total.requests(),
                rate(total.requests(), window), total.errorRate(), total.p95Ms(), byHealth, database, attention,
                all);
    }

    static int severity(Health health) {
        return switch (health) {
            case CRITICAL -> 0;
            case WARNING -> 1;
            case HEALTHY -> 2;
            case NO_DATA -> 3;
        };
    }

    // ------------------------------------------------------------------------------ helpers

    private Double rate(long requests, TimeWindow window) {
        return requests == 0 ? 0.0 : LatencyStats.round4(requests / window.elapsedSeconds(clock));
    }

    private static String connectionStatus(ServiceView service) {
        return service.connection() == null || service.connection().status() == null ? "NOT_CONFIGURED"
                : service.connection().status().toUpperCase(Locale.ROOT);
    }

    static String normalizeMethod(String method) {
        if (method == null || method.isBlank()) {
            throw ApiException.badRequest("METHOD_REQUIRED", "method is required together with route");
        }
        String m = method.trim().toUpperCase(Locale.ROOT);
        if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "OTHER").contains(m)) {
            throw ApiException.badRequest("INVALID_METHOD", "Unknown HTTP method " + method);
        }
        return m;
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
