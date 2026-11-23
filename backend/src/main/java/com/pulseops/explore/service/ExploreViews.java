package com.pulseops.explore.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulseops.monitoring.service.MonitoringViews.Window;

/** Response shapes of the logs and traces explorer. Durations in ms. */
public final class ExploreViews {

    private ExploreViews() {
    }

    public record LogEntry(UUID id, UUID serviceId, String serviceName, Instant occurredAt, String level,
                           String message, String logger, String traceId, String spanId, JsonNode attributes) {
    }

    /** {@code nextCursor} is null on the last page. */
    public record LogPage(Window window, List<LogEntry> logs, String nextCursor) {
    }

    public record LogBucket(Instant time, long total, Map<String, Long> byLevel) {
    }

    public record LogHistogram(Window window, List<LogBucket> buckets) {
    }

    /**
     * One trace. {@code rootName}/{@code rootService} come from the span without a parent,
     * or the earliest span when the root was not received ({@code complete = false}).
     */
    public record TraceSummary(String traceId, Instant startTime, double durationMs, long spans, long errorSpans,
                               long services, String rootName, UUID rootServiceId, String rootServiceName,
                               boolean complete) {
    }

    public record TracePage(Window window, String sort, List<TraceSummary> traces, String nextCursor) {
    }

    public record SpanView(String spanId, String parentSpanId, UUID serviceId, String serviceName, String name,
                           String kind, Instant startTime, double offsetMs, double durationMs, String status,
                           String statusMessage, int depth, boolean orphan, JsonNode attributes) {
    }

    /** A trace as a depth-first tree of spans, with the logs written while it ran. */
    public record TraceDetail(String traceId, Instant startTime, double durationMs, int spanCount, int errorSpans,
                              List<String> services, boolean complete, boolean truncated, List<SpanView> spans,
                              List<LogEntry> logs) {
    }
}
