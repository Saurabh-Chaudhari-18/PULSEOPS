package com.pulseops.incident.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseops.audit.service.AuditService;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.explore.service.Cursor;
import com.pulseops.explore.service.ExploreViews.LogEntry;
import com.pulseops.explore.service.ExploreViews.TraceSummary;
import com.pulseops.explore.service.LogExplorerService;
import com.pulseops.explore.service.TraceExplorerService;
import com.pulseops.incident.service.IncidentViews.Comment;
import com.pulseops.incident.service.IncidentViews.Evidence;
import com.pulseops.incident.service.IncidentViews.IncidentDetail;
import com.pulseops.incident.service.IncidentViews.IncidentPage;
import com.pulseops.incident.service.IncidentViews.IncidentSummaryView;
import com.pulseops.incident.service.IncidentViews.LinkedAnomaly;
import com.pulseops.incident.service.IncidentViews.TimelineEvent;
import com.pulseops.monitoring.service.DatabaseMonitoringService;
import com.pulseops.monitoring.service.DatabaseViews.OperationView;
import com.pulseops.monitoring.service.TimeWindow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Incident management: list, detail with evidence, manual creation, lifecycle edits, comments. */
@Service
public class IncidentService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    static final Duration EVIDENCE_LEAD = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditService audit;
    private final LogExplorerService logs;
    private final TraceExplorerService traces;
    private final DatabaseMonitoringService database;
    private final Clock clock;

    public IncidentService(JdbcTemplate jdbc, ObjectMapper objectMapper, AuditService audit, LogExplorerService logs,
                           TraceExplorerService traces, DatabaseMonitoringService database, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.logs = logs;
        this.traces = traces;
        this.database = database;
        this.clock = clock;
    }

    public record CreateRequest(@NotNull UUID projectId, UUID serviceId,
                                @NotBlank @Size(min = 3, max = 300) String title,
                                @Size(max = 5000) String summary,
                                @NotBlank String severity) {
    }

    /**
     * Partial update: absent fields are unchanged; {@code unassign=true} clears the assignee.
     */
    public record UpdateRequest(String status, String severity, UUID assigneeId, Boolean unassign,
                                @Size(min = 3, max = 300) String title, @Size(max = 5000) String summary,
                                @Size(max = 5000) String resolutionNotes) {
    }

    public record CommentRequest(@NotBlank @Size(max = 5000) String body) {
    }

    // ----------------------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public IncidentPage list(OrgAccess access, TimeWindow window, String status, String minSeverity, UUID projectId,
                             UUID serviceId, String cursorToken, Integer limit) {
        Cursor cursor = Cursor.decode(cursorToken);
        UUID cursorId = cursor == null ? null : uuid(cursor.id());
        Timestamp cursorTime = cursor == null ? null : Timestamp.from(cursor.time());
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        List<IncidentSummaryView> rows = jdbc.query(IncidentSql.LIST, (rs, n) -> summary(rs), access.organizationId(),
                statuses(status), severityRank(minSeverity), projectId, projectId, serviceId, serviceId, serviceId,
                Timestamp.from(window.from()), Timestamp.from(window.to()), cursorTime, cursorTime, cursorId, size + 1);
        String next = null;
        if (rows.size() > size) {
            rows = rows.subList(0, size);
            IncidentSummaryView last = rows.get(size - 1);
            next = new Cursor(last.detectedAt(), last.id().toString()).encode();
        }
        return new IncidentPage(window.from(), window.to(), List.copyOf(rows), next);
    }

    @Transactional(readOnly = true)
    public IncidentDetail get(OrgAccess access, UUID id) {
        IncidentSummaryView incident = summaryOrThrow(access, id);
        List<LinkedAnomaly> anomalies = jdbc.query(IncidentSql.ANOMALIES, (rs, n) -> anomaly(rs),
                access.organizationId(), id);
        List<TimelineEvent> timeline = jdbc.query(IncidentSql.EVENTS, (rs, n) -> new TimelineEvent(
                        rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getObject("actor_id", UUID.class),
                        rs.getString("actor_name"), rs.getString("old_value"), rs.getString("new_value"),
                        json(rs.getString("details")), instant(rs, "created_at")),
                access.organizationId(), id);
        List<Comment> comments = jdbc.query(IncidentSql.COMMENTS, (rs, n) -> new Comment(
                        rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class),
                        rs.getString("author_name"), rs.getString("body"), instant(rs, "created_at"),
                        instant(rs, "updated_at")),
                access.organizationId(), id);
        Map<UUID, String> services = new LinkedHashMap<>();
        anomalies.forEach(a -> services.putIfAbsent(a.serviceId(), a.serviceName()));
        if (incident.serviceId() != null) {
            services.putIfAbsent(incident.serviceId(), incident.serviceName());
        }
        return new IncidentDetail(incident, List.copyOf(services.values()), anomalies, timeline, comments,
                evidence(access, incident, anomalies, services.keySet()));
    }

    /** Telemetry around the incident, per affected service, merged and capped. */
    private Evidence evidence(OrgAccess access, IncidentSummaryView incident, List<LinkedAnomaly> anomalies,
                              Set<UUID> services) {
        Instant now = clock.instant();
        Instant start = anomalies.stream().map(LinkedAnomaly::windowStart).min(Comparator.naturalOrder())
                .orElse(incident.detectedAt().minus(Duration.ofMinutes(10))).minus(EVIDENCE_LEAD);
        Instant end = incident.closedAt() != null ? incident.closedAt()
                : incident.resolvedAt() != null ? incident.resolvedAt() : now;
        if (end.isAfter(now)) {
            end = now;
        }
        if (Duration.between(start, end).compareTo(TimeWindow.MAX_RANGE) > 0) {
            start = end.minus(TimeWindow.MAX_RANGE);
        }
        if (!start.isBefore(end)) {
            start = end.minus(Duration.ofMinutes(1));
        }
        TimeWindow window = TimeWindow.parse(start.toString(), end.toString(), null, clock);
        List<LogEntry> logEntries = new ArrayList<>();
        List<TraceSummary> slowest = new ArrayList<>();
        List<TraceSummary> failing = new ArrayList<>();
        List<OperationView> queries = new ArrayList<>();
        for (UUID serviceId : services) {
            try {
                collect(access, window, serviceId, logEntries, slowest, failing, queries);
            } catch (ApiException e) {
                // A service removed since the incident: its evidence is simply absent.
            }
        }
        logEntries.sort(Comparator.comparing(LogEntry::occurredAt).reversed());
        slowest.sort(Comparator.comparingDouble(TraceSummary::durationMs).reversed());
        failing.sort(Comparator.comparing(TraceSummary::startTime).reversed());
        queries.sort(Comparator.comparing(OperationView::p95Ms, Comparator.nullsLast(Comparator.reverseOrder())));
        return new Evidence(window.from(), window.to(), cap(logEntries, 50), cap(distinct(slowest), 10),
                cap(distinct(failing), 10), cap(queries, 10));
    }

    private void collect(OrgAccess access, TimeWindow window, UUID serviceId, List<LogEntry> logEntries,
                         List<TraceSummary> slowest, List<TraceSummary> failing, List<OperationView> queries) {
        logEntries.addAll(logs.search(access, window,
                new LogExplorerService.LogFilter(serviceId, "WARN", null, null, null), null, 20).logs());
        slowest.addAll(traces.search(access, window,
                new TraceExplorerService.TraceFilter(serviceId, null, false, null, "slowest"), null, 5).traces());
        failing.addAll(traces.search(access, window,
                new TraceExplorerService.TraceFilter(serviceId, null, true, null, "recent"), null, 5).traces());
        queries.addAll(database.operations(access, serviceId, window, "p95", null, 5, null).operations());
    }

    // ---------------------------------------------------------------------------------- write

    @Transactional
    public IncidentDetail create(OrgAccess access, CreateRequest request) {
        UUID org = access.organizationId();
        String severity = severity(request.severity());
        if (count(IncidentSql.PROJECT_ACTIVE, org, request.projectId()) == 0) {
            throw ApiException.notFound("PROJECT_NOT_FOUND", "No active project with this ID");
        }
        if (request.serviceId() != null
                && count(IncidentSql.SERVICE_IN_PROJECT, org, request.serviceId(), request.projectId()) == 0) {
            throw ApiException.badRequest("SERVICE_NOT_IN_PROJECT", "The service does not belong to this project");
        }
        UUID id = jdbc.queryForObject(IncidentSql.CREATE_MANUAL, UUID.class, org, request.projectId(),
                request.serviceId(), request.title().trim(), blankToNull(request.summary()), severity, access.userId());
        event(access, id, "created", null, "open", Map.of("source", "manual"));
        audit.record(access.userId(), "incident.created", "incident", id, Map.of("title", request.title().trim(),
                "severity", severity));
        return get(access, id);
    }

    @Transactional
    public IncidentDetail update(OrgAccess access, UUID id, UpdateRequest request) {
        UUID org = access.organizationId();
        List<Map<String, Object>> locked = jdbc.queryForList(IncidentSql.LOCK, org, id);
        if (locked.isEmpty()) {
            throw notFound();
        }
        Map<String, Object> row = locked.get(0);
        String oldStatus = (String) row.get("status");
        String oldSeverity = (String) row.get("severity");
        UUID oldAssignee = (UUID) row.get("assigned_to");
        String oldTitle = (String) row.get("title");
        String oldSummary = (String) row.get("summary");
        String oldNotes = (String) row.get("resolution_notes");

        String status = oldStatus;
        if (request.status() != null) {
            status = request.status().trim().toLowerCase(Locale.ROOT);
            if (!status.equals(oldStatus)) {
                IncidentTransitions.check(oldStatus, status);
            }
        }
        String severity = request.severity() == null ? oldSeverity : severity(request.severity());
        UUID assignee = Boolean.TRUE.equals(request.unassign()) ? null
                : request.assigneeId() == null ? oldAssignee : request.assigneeId();
        if (assignee != null && !assignee.equals(oldAssignee) && count(IncidentSql.ACTIVE_MEMBER, org, assignee) == 0) {
            throw ApiException.badRequest("ASSIGNEE_NOT_MEMBER", "The assignee must be an active member of the organization");
        }
        String title = request.title() == null ? oldTitle : request.title().trim();
        String summary = request.summary() == null ? oldSummary : blankToNull(request.summary());
        String notes = request.resolutionNotes() == null ? oldNotes : blankToNull(request.resolutionNotes());

        jdbc.update(IncidentSql.UPDATE, status, severity, assignee, title, summary, notes, status, status, status,
                status, id, org);

        Map<String, Object> changes = new LinkedHashMap<>();
        if (!status.equals(oldStatus)) {
            event(access, id, "status_changed", oldStatus, status, Map.of());
            changes.put("status", status);
        }
        if (!severity.equals(oldSeverity)) {
            event(access, id, "severity_changed", oldSeverity, severity, Map.of());
            changes.put("severity", severity);
        }
        if (!Objects.equals(assignee, oldAssignee)) {
            event(access, id, "assigned", str(oldAssignee), str(assignee), Map.of());
            changes.put("assignee", str(assignee));
        }
        if (!Objects.equals(title, oldTitle) || !Objects.equals(summary, oldSummary)) {
            event(access, id, "updated", null, null, Map.of("title", !Objects.equals(title, oldTitle),
                    "summary", !Objects.equals(summary, oldSummary)));
            changes.put("text", true);
        }
        if (!Objects.equals(notes, oldNotes)) {
            event(access, id, "resolved_notes_set", null, null, Map.of());
            changes.put("resolutionNotes", true);
        }
        if (!changes.isEmpty()) {
            audit.record(access.userId(), "incident.updated", "incident", id, changes);
        }
        return get(access, id);
    }

    @Transactional
    public Comment comment(OrgAccess access, UUID id, CommentRequest request) {
        summaryOrThrow(access, id);
        UUID commentId = jdbc.queryForObject(IncidentSql.ADD_COMMENT, UUID.class, access.organizationId(), id,
                access.userId(), request.body().trim());
        event(access, id, "comment_added", null, null, Map.of("commentId", commentId.toString()));
        return jdbc.query(IncidentSql.COMMENTS, (rs, n) -> new Comment(rs.getObject("id", UUID.class),
                        rs.getObject("author_id", UUID.class), rs.getString("author_name"), rs.getString("body"),
                        instant(rs, "created_at"), instant(rs, "updated_at")),
                access.organizationId(), id).stream().filter(c -> c.id().equals(commentId)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteComment(OrgAccess access, UUID id, UUID commentId) {
        summaryOrThrow(access, id);
        if (jdbc.update(IncidentSql.DELETE_COMMENT, access.organizationId(), id, commentId, access.userId()) == 0) {
            throw ApiException.notFound("COMMENT_NOT_FOUND", "No such comment of yours on this incident");
        }
        event(access, id, "comment_deleted", null, null, Map.of("commentId", commentId.toString()));
    }

    // -------------------------------------------------------------------------------- helpers

    /** Default: active (open, investigating, acknowledged). */
    static String statuses(String status) {
        String s = status == null || status.isBlank() ? "active" : status.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "active" -> "open,investigating,acknowledged";
            case "open", "investigating", "acknowledged", "resolved", "closed" -> s;
            case "all" -> "open,investigating,acknowledged,resolved,closed";
            default -> throw ApiException.badRequest("INVALID_STATUS",
                    "status must be active, open, investigating, acknowledged, resolved, closed or all");
        };
    }

    static int severityRank(String minSeverity) {
        if (minSeverity == null || minSeverity.isBlank()) {
            return 1;
        }
        int rank = IncidentRules.rank(minSeverity.trim().toLowerCase(Locale.ROOT));
        if (rank == 0) {
            throw ApiException.badRequest("INVALID_SEVERITY", "severity must be low, medium, high or critical");
        }
        return rank;
    }

    static String severity(String text) {
        String s = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (IncidentRules.rank(s) == 0) {
            throw ApiException.badRequest("INVALID_SEVERITY", "severity must be low, medium, high or critical");
        }
        return s;
    }

    private IncidentSummaryView summaryOrThrow(OrgAccess access, UUID id) {
        List<IncidentSummaryView> rows = jdbc.query(IncidentSql.GET, (rs, n) -> summary(rs), access.organizationId(), id);
        if (rows.isEmpty()) {
            throw notFound();
        }
        return rows.get(0);
    }

    private static ApiException notFound() {
        return ApiException.notFound("INCIDENT_NOT_FOUND", "No such incident");
    }

    private void event(OrgAccess access, UUID id, String type, String oldValue, String newValue, Map<String, ?> details) {
        String json;
        try {
            json = objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        jdbc.update(IncidentSql.EVENT, access.organizationId(), id, type, access.userId(), oldValue, newValue, json);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static IncidentSummaryView summary(ResultSet rs) throws SQLException {
        return new IncidentSummaryView(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("project_name"), rs.getObject("service_id", UUID.class), rs.getString("service_name"),
                rs.getString("title"), rs.getString("summary"), rs.getString("severity"), rs.getString("status"),
                rs.getString("source"), instant(rs, "detected_at"), instant(rs, "acknowledged_at"),
                instant(rs, "resolved_at"), instant(rs, "closed_at"), rs.getObject("assigned_to", UUID.class),
                rs.getString("assignee_name"), rs.getObject("created_by", UUID.class), rs.getString("resolution_notes"),
                instant(rs, "updated_at"), rs.getLong("anomaly_count"));
    }

    private static LinkedAnomaly anomaly(ResultSet rs) throws SQLException {
        double expected = rs.getDouble("expected_value");
        Double expectedValue = rs.wasNull() ? null : expected;
        return new LinkedAnomaly(rs.getObject("id", UUID.class), rs.getObject("service_id", UUID.class),
                rs.getString("service_name"), rs.getString("metric"), rs.getString("scope"),
                rs.getString("anomaly_type"), rs.getString("severity"), rs.getDouble("observed_value"), expectedValue,
                rs.getDouble("score"), rs.getString("reason"), instant(rs, "detected_at"), instant(rs, "last_seen_at"),
                instant(rs, "window_start"), instant(rs, "window_end"), rs.getString("status"),
                rs.getInt("occurrences"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private JsonNode json(String text) {
        try {
            return objectMapper.readTree(text == null ? "{}" : text);
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode();
        }
    }

    private static UUID uuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_CURSOR", "The page cursor is not valid; start from the first page");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String str(UUID id) {
        return id == null ? null : id.toString();
    }

    private static <T> List<T> cap(List<T> list, int max) {
        return List.copyOf(list.subList(0, Math.min(max, list.size())));
    }

    private static List<TraceSummary> distinct(List<TraceSummary> traces) {
        Set<String> seen = new LinkedHashSet<>();
        List<TraceSummary> out = new ArrayList<>();
        for (TraceSummary t : traces) {
            if (seen.add(t.traceId())) {
                out.add(t);
            }
        }
        return out;
    }
}
