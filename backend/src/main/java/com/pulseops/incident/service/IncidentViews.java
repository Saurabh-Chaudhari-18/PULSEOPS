package com.pulseops.incident.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulseops.explore.service.ExploreViews.LogEntry;
import com.pulseops.explore.service.ExploreViews.TraceSummary;
import com.pulseops.monitoring.service.DatabaseViews.OperationView;

/** Response shapes of the incident API. */
public final class IncidentViews {

    private IncidentViews() {
    }

    public record IncidentSummaryView(UUID id, UUID projectId, String projectName, UUID serviceId, String serviceName,
                                      String title, String summary, String severity, String status, String source,
                                      Instant detectedAt, Instant acknowledgedAt, Instant resolvedAt, Instant closedAt,
                                      UUID assignedTo, String assigneeName, UUID createdBy, String resolutionNotes,
                                      Instant updatedAt, long anomalyCount) {
    }

    public record IncidentPage(Instant from, Instant to, List<IncidentSummaryView> incidents, String nextCursor) {
    }

    public record LinkedAnomaly(UUID id, UUID serviceId, String serviceName, String metric, String scope, String type,
                                String severity, double observed, Double expected, double score, String reason,
                                Instant detectedAt, Instant lastSeenAt, Instant windowStart, Instant windowEnd,
                                String status, int occurrences) {
    }

    public record TimelineEvent(UUID id, String type, UUID actorId, String actorName, String oldValue,
                                String newValue, JsonNode details, Instant at) {
    }

    public record Comment(UUID id, UUID authorId, String authorName, String body, Instant createdAt,
                          Instant updatedAt) {
    }

    /**
     * Telemetry gathered around the incident for the affected services: warnings/errors, the
     * slowest and the failing traces, and the slowest database statements of the period.
     */
    public record Evidence(Instant from, Instant to, List<LogEntry> logs, List<TraceSummary> slowestTraces,
                           List<TraceSummary> errorTraces, List<OperationView> slowestQueries) {
    }

    public record IncidentDetail(IncidentSummaryView incident, List<String> affectedServices,
                                 List<LinkedAnomaly> anomalies, List<TimelineEvent> timeline, List<Comment> comments,
                                 Evidence evidence) {
    }
}
