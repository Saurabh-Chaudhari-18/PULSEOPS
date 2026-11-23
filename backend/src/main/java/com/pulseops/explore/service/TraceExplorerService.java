package com.pulseops.explore.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.persistence.LikePatterns;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.explore.service.ExploreViews.LogEntry;
import com.pulseops.explore.service.ExploreViews.SpanView;
import com.pulseops.explore.service.ExploreViews.TraceDetail;
import com.pulseops.explore.service.ExploreViews.TracePage;
import com.pulseops.explore.service.ExploreViews.TraceSummary;
import com.pulseops.monitoring.service.MonitoringViews.Window;
import com.pulseops.monitoring.service.TimeWindow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Distributed traces: search (recent or slowest, filtered by service, minimum duration,
 * errors and root operation name) and a single trace as a span tree with its logs.
 */
@Service
public class TraceExplorerService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    public static final int MAX_SPANS = 2_000;
    public static final int MAX_TRACE_LOGS = 500;

    private final JdbcTemplate jdbc;
    private final LogExplorerService logs;

    public TraceExplorerService(JdbcTemplate jdbc, LogExplorerService logs) {
        this.jdbc = jdbc;
        this.logs = logs;
    }

    public record TraceFilter(UUID serviceId, Double minDurationMs, boolean errorsOnly, String name, String sort) {
    }

    @Transactional(readOnly = true)
    public TracePage search(OrgAccess access, TimeWindow window, TraceFilter filter, String cursorToken,
                            Integer limit) {
        String sort = filter.sort() == null || filter.sort().isBlank() ? "recent"
                : filter.sort().trim().toLowerCase(Locale.ROOT);
        if (!sort.equals("recent") && !sort.equals("slowest")) {
            throw ApiException.badRequest("INVALID_SORT", "sort must be recent or slowest");
        }
        boolean slowest = sort.equals("slowest");
        Cursor cursor = slowest ? null : Cursor.decode(cursorToken);
        if (cursor != null) {
            LogExplorerService.traceId(cursor.id());
        }
        Double minDuration = filter.minDurationMs();
        if (minDuration != null && (minDuration < 0 || minDuration > 86_400_000)) {
            throw ApiException.badRequest("INVALID_DURATION", "minDurationMs must be between 0 and 86400000");
        }
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        String pattern = LikePatterns.contains(filter.name());
        Timestamp cursorTime = cursor == null ? null : Timestamp.from(cursor.time());
        Object[] args = {access.organizationId(), Timestamp.from(window.from()), Timestamp.from(window.to()),
                filter.serviceId(), filter.serviceId(), access.organizationId(), minDuration, minDuration,
                filter.errorsOnly(), pattern, pattern, cursorTime, cursorTime, cursor == null ? null : cursor.id(),
                slowest, size + 1};
        List<TraceSummary> rows = jdbc.query(ExploreSql.TRACES, (rs, n) -> new TraceSummary(
                rs.getString("trace_id"), rs.getTimestamp("start_time").toInstant(),
                round(rs.getDouble("duration_ms")), rs.getLong("spans"), rs.getLong("errors"), rs.getLong("services"),
                rs.getString("root_name"), rs.getObject("root_service_id", UUID.class),
                rs.getString("root_service_name"), rs.getBoolean("has_root")), args);
        String next = null;
        if (rows.size() > size) {
            rows = rows.subList(0, size);
            if (!slowest) {
                TraceSummary last = rows.get(size - 1);
                next = new Cursor(last.startTime(), last.traceId()).encode();
            }
        }
        return new TracePage(Window.of(window), sort, List.copyOf(rows), next);
    }

    /** Raw span row before tree ordering. */
    record SpanRow(String spanId, String parentSpanId, UUID serviceId, String serviceName, String name, String kind,
                   Instant startTime, double durationMs, String status, String statusMessage, String attributes) {
    }

    @Transactional(readOnly = true)
    public TraceDetail trace(OrgAccess access, String traceIdText) {
        String traceId = LogExplorerService.traceId(traceIdText);
        if (traceId == null) {
            throw ApiException.badRequest("INVALID_TRACE_ID", "A trace ID is 32 hexadecimal characters");
        }
        List<SpanRow> rows = jdbc.query(ExploreSql.TRACE_SPANS, (rs, n) -> new SpanRow(rs.getString("span_id"),
                        rs.getString("parent_span_id"), rs.getObject("service_id", UUID.class),
                        rs.getString("service_name"), rs.getString("name"), rs.getString("kind"),
                        rs.getTimestamp("start_time").toInstant(), rs.getDouble("duration_ms"),
                        rs.getString("status_code"), rs.getString("status_message"), rs.getString("attributes")),
                access.organizationId(), traceId, MAX_SPANS + 1);
        if (rows.isEmpty()) {
            // Same answer for "never existed" and "another tenant's": no information leak.
            throw ApiException.notFound("TRACE_NOT_FOUND", "No trace with this ID in this organization");
        }
        boolean truncated = rows.size() > MAX_SPANS;
        if (truncated) {
            rows = rows.subList(0, MAX_SPANS);
        }
        List<LogEntry> traceLogs = jdbc.query(ExploreSql.TRACE_LOGS, (rs, n) -> logs.logEntry(rs),
                access.organizationId(), traceId, MAX_TRACE_LOGS);
        return detail(traceId, rows, truncated, traceLogs, logs::json);
    }

    static TraceDetail detail(String traceId, List<SpanRow> rows, boolean truncated, List<LogEntry> traceLogs,
                              Function<String, JsonNode> json) {
        Instant start = rows.stream().map(SpanRow::startTime).min(Comparator.naturalOrder()).orElseThrow();
        Instant end = rows.stream().map(r -> r.startTime().plusNanos((long) (r.durationMs() * 1_000_000)))
                .max(Comparator.naturalOrder()).orElseThrow();
        List<TraceTree.Node<SpanRow>> ordered = TraceTree.order(rows, SpanRow::spanId, SpanRow::parentSpanId,
                Comparator.comparing(SpanRow::startTime).thenComparing(SpanRow::spanId));
        List<SpanView> spans = new ArrayList<>(ordered.size());
        Set<String> services = new LinkedHashSet<>();
        int errors = 0;
        boolean complete = false;
        for (TraceTree.Node<SpanRow> node : ordered) {
            SpanRow r = node.span();
            services.add(r.serviceName());
            errors += "ERROR".equals(r.status()) ? 1 : 0;
            complete |= r.parentSpanId() == null;
            spans.add(new SpanView(r.spanId(), r.parentSpanId(), r.serviceId(), r.serviceName(), r.name(), r.kind(),
                    r.startTime(), round(Duration.between(start, r.startTime()).toNanos() / 1_000_000.0),
                    round(r.durationMs()), r.status(), r.statusMessage(), node.depth(), node.orphan(),
                    json.apply(r.attributes())));
        }
        return new TraceDetail(traceId, start, round(Duration.between(start, end).toNanos() / 1_000_000.0),
                spans.size(), errors, List.copyOf(services), complete, truncated, spans, traceLogs);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
