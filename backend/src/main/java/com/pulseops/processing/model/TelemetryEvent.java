package com.pulseops.processing.model;

import java.time.Instant;
import java.util.Map;

/**
 * A validated, normalized and redacted event, independent of the wire protocol it arrived
 * on (REST now, OTLP in Phase 7). Optional parts are null when not applicable.
 */
public record TelemetryEvent(
        EventType type,
        String name,
        Instant occurredAt,
        Map<String, Object> attributes,
        Http http,
        Metric metric,
        Failure error,
        String traceId,
        String spanId) {

    /**
     * @param method     normalized method (GET, POST, …, OTHER)
     * @param route      low-cardinality route template, e.g. /orders/{id}
     * @param statusCode HTTP status, or null if unknown
     */
    public record Http(String method, String route, Integer statusCode, double durationMs) {

        /** 1xx..5xx; unknown status is treated as 2xx (request completed without a reported failure). */
        public String statusClass() {
            if (statusCode == null) {
                return "2xx";
            }
            return (statusCode / 100) + "xx";
        }

        /** Server errors count toward the error rate; 4xx are client errors and do not. */
        public boolean isServerError() {
            return statusCode != null && statusCode >= 500;
        }
    }

    public record Metric(double value, String unit) {
    }

    public record Failure(String type, String message, String stackTrace) {
    }
}
