package com.pulseops.processing.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A validated span, protocol-independent. IDs are lowercase hex (32/16 chars); attributes
 * are already filtered and redacted.
 *
 * @param kind       INTERNAL, SERVER, CLIENT, PRODUCER or CONSUMER
 * @param statusCode UNSET, OK or ERROR
 */
public record SpanRecord(
        String traceId,
        String spanId,
        String parentSpanId,
        String name,
        String kind,
        Instant startTime,
        double durationMs,
        String statusCode,
        String statusMessage,
        Map<String, Object> attributes,
        List<SpanEvent> events) {

    public SpanRecord {
        attributes = Map.copyOf(attributes);
        events = List.copyOf(events);
    }

    public record SpanEvent(String name, Instant time, Map<String, Object> attributes) {

        public SpanEvent {
            attributes = Map.copyOf(attributes);
        }
    }
}
