package com.pulseops.ingest.api;

import java.util.List;
import java.util.Map;

/**
 * Wire format of {@code POST /api/v1/ingest/v1/events}. Deliberately lenient types (e.g.
 * timestamp as String) so validation can report precise per-field errors instead of a
 * generic parse failure. See docs/API.md for the contract.
 */
public final class IngestRequests {

    private IngestRequests() {
    }

    /**
     * @param service name of the target service in the key's project; required for project-wide
     *                keys, optional (but must match) for service-scoped keys
     */
    public record EventBatch(String service, List<Event> events) {
    }

    public record Event(
            String type,
            String name,
            String timestamp,
            Map<String, Object> attributes,
            // performance
            String httpMethod,
            String route,
            String path,
            Integer statusCode,
            Double durationMs,
            // metric
            Double value,
            String unit,
            // error
            String message,
            String errorType,
            String stackTrace,
            // correlation
            String traceId,
            String spanId) {
    }

    public record Accepted(String batchId, int accepted) {
    }
}
