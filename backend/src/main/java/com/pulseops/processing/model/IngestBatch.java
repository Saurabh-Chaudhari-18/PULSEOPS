package com.pulseops.processing.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.pulseops.integration.domain.IntegrationType;

/**
 * A unit of asynchronous work: validated telemetry for ONE service of ONE tenant, from any
 * protocol (REST events, or OTLP spans/logs/metrics).
 *
 * @param events          raw REST events (stored in telemetry_events)
 * @param derivedRequests HTTP requests derived from server spans: aggregated into api_metrics
 *                        but not stored as raw events (the span itself is the raw record)
 * @param spans           distributed trace spans
 * @param logs            log lines (OTLP logs, and exceptions recorded on spans)
 * @param dbOperations    database calls derived from client spans
 * @param metricPoints    one-minute metric aggregates
 */
public record IngestBatch(
        UUID batchId,
        UUID organizationId,
        UUID projectId,
        UUID serviceId,
        UUID credentialId,
        IntegrationType source,
        Instant receivedAt,
        List<TelemetryEvent> events,
        List<TelemetryEvent> derivedRequests,
        List<SpanRecord> spans,
        List<LogRecordData> logs,
        List<DbOperationRecord> dbOperations,
        List<MetricPointRecord> metricPoints) {

    public IngestBatch {
        events = List.copyOf(events);
        derivedRequests = List.copyOf(derivedRequests);
        spans = List.copyOf(spans);
        logs = List.copyOf(logs);
        dbOperations = List.copyOf(dbOperations);
        metricPoints = List.copyOf(metricPoints);
    }

    /** REST batch: events only. */
    public IngestBatch(UUID batchId, UUID organizationId, UUID projectId, UUID serviceId, UUID credentialId,
                       IntegrationType source, Instant receivedAt, List<TelemetryEvent> events) {
        this(batchId, organizationId, projectId, serviceId, credentialId, source, receivedAt, events,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public int size() {
        return events.size() + spans.size() + logs.size() + metricPoints.size();
    }

    /** All HTTP observations to aggregate: REST performance events plus server spans. */
    public List<TelemetryEvent> requestsForAggregation() {
        if (derivedRequests.isEmpty()) {
            return events;
        }
        if (events.isEmpty()) {
            return derivedRequests;
        }
        return java.util.stream.Stream.concat(events.stream(), derivedRequests.stream()).toList();
    }
}
