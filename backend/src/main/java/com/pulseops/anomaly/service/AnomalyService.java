package com.pulseops.anomaly.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseops.audit.service.AuditService;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.explore.service.Cursor;
import com.pulseops.monitoring.service.MonitoringViews.Window;
import com.pulseops.monitoring.service.TimeWindow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Anomaly read model and the one user action on it: dismissing a false positive. */
@Service
public class AnomalyService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    static final List<String> SEVERITIES = List.of("low", "medium", "high", "critical");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditService audit;

    public AnomalyService(JdbcTemplate jdbc, ObjectMapper objectMapper, AuditService audit) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    public record AnomalyView(UUID id, UUID serviceId, String serviceName, UUID projectId, String metric,
                              String scope, String type, String severity, double observed, Double expected,
                              double score, String reason, Instant detectedAt, Instant lastSeenAt,
                              Instant windowStart, Instant windowEnd, int occurrences, String status,
                              Instant resolvedAt, JsonNode evidence) {
    }

    public record AnomalyPage(Window window, List<AnomalyView> anomalies, String nextCursor) {
    }

    @Transactional(readOnly = true)
    public AnomalyPage list(OrgAccess access, TimeWindow window, String status, UUID serviceId, String minSeverity,
                            String cursorToken, Integer limit) {
        String statuses = statuses(status);
        int rank = severityRank(minSeverity);
        Cursor cursor = Cursor.decode(cursorToken);
        UUID cursorId = null;
        if (cursor != null) {
            try {
                cursorId = UUID.fromString(cursor.id());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("INVALID_CURSOR", "The page cursor is not valid; start from the first page");
            }
        }
        Timestamp cursorTime = cursor == null ? null : Timestamp.from(cursor.time());
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        List<AnomalyView> rows = jdbc.query(AnomalySql.LIST, (rs, n) -> view(rs), access.organizationId(), statuses,
                serviceId, serviceId, rank, Timestamp.from(window.from()), Timestamp.from(window.to()), cursorTime,
                cursorTime, cursorId, size + 1);
        String next = null;
        if (rows.size() > size) {
            rows = rows.subList(0, size);
            AnomalyView last = rows.get(size - 1);
            next = new Cursor(last.detectedAt(), last.id().toString()).encode();
        }
        return new AnomalyPage(Window.of(window), List.copyOf(rows), next);
    }

    @Transactional(readOnly = true)
    public AnomalyView get(OrgAccess access, UUID id) {
        List<AnomalyView> rows = jdbc.query(AnomalySql.GET, (rs, n) -> view(rs), access.organizationId(), id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("ANOMALY_NOT_FOUND", "No such anomaly");
        }
        return rows.get(0);
    }

    @Transactional
    public AnomalyView dismiss(OrgAccess access, UUID id) {
        AnomalyView current = get(access, id);
        if (jdbc.update(AnomalySql.DISMISS, access.organizationId(), id) == 0) {
            throw ApiException.conflict("ANOMALY_NOT_ACTIVE", "Only open or linked anomalies can be dismissed (status is "
                    + current.status() + ")");
        }
        audit.record(access.userId(), "anomaly.dismissed", "anomaly", id,
                Map.of("serviceId", current.serviceId(), "metric", current.metric(), "scope", current.scope()));
        return get(access, id);
    }

    /** Default: active (open and linked). */
    static String statuses(String status) {
        String s = status == null || status.isBlank() ? "active" : status.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "active" -> "open,linked";
            case "open", "linked", "dismissed", "resolved" -> s;
            case "all" -> "open,linked,dismissed,resolved";
            default -> throw ApiException.badRequest("INVALID_STATUS",
                    "status must be active, open, linked, resolved, dismissed or all");
        };
    }

    /** 1-based rank for SQL array_position; default low (everything). */
    static int severityRank(String minSeverity) {
        if (minSeverity == null || minSeverity.isBlank()) {
            return 1;
        }
        int index = SEVERITIES.indexOf(minSeverity.trim().toLowerCase(Locale.ROOT));
        if (index < 0) {
            throw ApiException.badRequest("INVALID_SEVERITY", "minSeverity must be low, medium, high or critical");
        }
        return index + 1;
    }

    private AnomalyView view(ResultSet rs) throws SQLException {
        double expected = rs.getDouble("expected_value");
        Double expectedValue = rs.wasNull() ? null : expected;
        Timestamp resolved = rs.getTimestamp("resolved_at");
        return new AnomalyView(rs.getObject("id", UUID.class), rs.getObject("service_id", UUID.class),
                rs.getString("service_name"), rs.getObject("project_id", UUID.class), rs.getString("metric"),
                rs.getString("scope"), rs.getString("anomaly_type"), rs.getString("severity"),
                rs.getDouble("observed_value"), expectedValue, rs.getDouble("score"), rs.getString("reason"),
                rs.getTimestamp("detected_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant(),
                rs.getTimestamp("window_start").toInstant(), rs.getTimestamp("window_end").toInstant(),
                rs.getInt("occurrences"), rs.getString("status"), resolved == null ? null : resolved.toInstant(),
                json(rs.getString("evidence")));
    }

    private JsonNode json(String text) {
        try {
            return objectMapper.readTree(text == null ? "{}" : text);
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode();
        }
    }
}
