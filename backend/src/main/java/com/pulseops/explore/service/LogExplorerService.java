package com.pulseops.explore.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.persistence.LikePatterns;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.explore.service.ExploreViews.LogBucket;
import com.pulseops.explore.service.ExploreViews.LogEntry;
import com.pulseops.explore.service.ExploreViews.LogHistogram;
import com.pulseops.explore.service.ExploreViews.LogPage;
import com.pulseops.monitoring.service.MonitoringViews.Window;
import com.pulseops.monitoring.service.TimeWindow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Log search across the organization or one service: time range, minimum level or exact
 * levels, message text, trace ID; newest first with keyset pagination, plus a volume
 * histogram per level. Messages were redacted at ingestion.
 */
@Service
public class LogExplorerService {

    /** Severity order; a minimum level selects it and everything above. */
    public enum Level { TRACE, DEBUG, INFO, WARN, ERROR, FATAL }

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;
    public static final int MAX_QUERY_LENGTH = 200;
    static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LogExplorerService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Filters shared by search and histogram. */
    public record LogFilter(UUID serviceId, String minLevel, String levels, String query, String traceId) {
    }

    @Transactional(readOnly = true)
    public LogPage search(OrgAccess access, TimeWindow window, LogFilter filter, String cursorToken, Integer limit) {
        Cursor cursor = Cursor.decode(cursorToken);
        UUID cursorId = cursor == null ? null : uuid(cursor.id());
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        List<Object> args = filterArgs(access, window, filter);
        Timestamp cursorTime = cursor == null ? null : Timestamp.from(cursor.time());
        args.add(cursorTime);
        args.add(cursorTime);
        args.add(cursorId);
        args.add(size + 1);
        List<LogEntry> rows = jdbc.query(ExploreSql.LOGS, (rs, n) -> logEntry(rs), args.toArray());
        String next = null;
        if (rows.size() > size) {
            rows = rows.subList(0, size);
            LogEntry last = rows.get(size - 1);
            next = new Cursor(last.occurredAt(), last.id().toString()).encode();
        }
        return new LogPage(Window.of(window), List.copyOf(rows), next);
    }

    @Transactional(readOnly = true)
    public LogHistogram histogram(OrgAccess access, TimeWindow window, LogFilter filter) {
        List<Object> args = new ArrayList<>(List.of(window.stepSeconds(), window.stepSeconds()));
        args.addAll(filterArgs(access, window, filter));
        Map<Instant, Map<String, Long>> byTime = new HashMap<>();
        jdbc.query(ExploreSql.LOG_HISTOGRAM, rs -> {
            byTime.computeIfAbsent(rs.getTimestamp("t").toInstant(), t -> new HashMap<>())
                    .put(rs.getString("level"), rs.getLong("n"));
        }, args.toArray());
        List<LogBucket> buckets = new ArrayList<>();
        for (Instant t : window.points()) {
            Map<String, Long> counts = new LinkedHashMap<>();
            long total = 0;
            for (Level level : Level.values()) {
                long n = byTime.getOrDefault(t, Map.of()).getOrDefault(level.name(), 0L);
                counts.put(level.name(), n);
                total += n;
            }
            buckets.add(new LogBucket(t, total, counts));
        }
        return new LogHistogram(Window.of(window), buckets);
    }

    private List<Object> filterArgs(OrgAccess access, TimeWindow window, LogFilter filter) {
        String levels = levels(filter.minLevel(), filter.levels()).stream().map(Enum::name)
                .collect(Collectors.joining(","));
        String query = filter.query() == null || filter.query().isBlank() ? null : filter.query().trim();
        if (query != null && query.length() > MAX_QUERY_LENGTH) {
            throw ApiException.badRequest("QUERY_TOO_LONG", "Search text is limited to " + MAX_QUERY_LENGTH
                    + " characters");
        }
        String pattern = LikePatterns.contains(query);
        String traceId = traceId(filter.traceId());
        List<Object> args = new ArrayList<>();
        args.add(access.organizationId());
        args.add(Timestamp.from(window.from()));
        args.add(Timestamp.from(window.to()));
        args.add(filter.serviceId());
        args.add(filter.serviceId());
        args.add(levels);
        args.add(pattern);
        args.add(pattern);
        args.add(traceId);
        args.add(traceId);
        return args;
    }

    /** Exact levels win over a minimum level; neither means all levels. */
    static Set<Level> levels(String minLevel, String exact) {
        if (exact != null && !exact.isBlank()) {
            Set<Level> out = EnumSet.noneOf(Level.class);
            for (String part : exact.split(",")) {
                if (!part.isBlank()) {
                    out.add(level(part));
                }
            }
            return out.isEmpty() ? EnumSet.allOf(Level.class) : out;
        }
        if (minLevel == null || minLevel.isBlank()) {
            return EnumSet.allOf(Level.class);
        }
        return EnumSet.range(level(minLevel), Level.FATAL);
    }

    private static Level level(String text) {
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("WARNING")) {
            normalized = "WARN";
        }
        try {
            return Level.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_LEVEL", "Unknown log level '" + text + "' (use "
                    + EnumSet.allOf(Level.class).stream().map(Enum::name).collect(Collectors.joining(", ")) + ")");
        }
    }

    static String traceId(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (!TRACE_ID.matcher(normalized).matches()) {
            throw ApiException.badRequest("INVALID_TRACE_ID", "A trace ID is 32 hexadecimal characters");
        }
        return normalized;
    }

    private static UUID uuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_CURSOR", "The page cursor is not valid; start from the first page");
        }
    }

    LogEntry logEntry(ResultSet rs) throws SQLException {
        return new LogEntry(rs.getObject("id", UUID.class), rs.getObject("service_id", UUID.class),
                rs.getString("service_name"), rs.getTimestamp("occurred_at").toInstant(), rs.getString("level"),
                rs.getString("message"), rs.getString("logger"), rs.getString("trace_id"), rs.getString("span_id"),
                json(rs.getString("attributes")));
    }

    JsonNode json(String text) {
        try {
            return objectMapper.readTree(text == null ? "{}" : text);
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode();
        }
    }
}
