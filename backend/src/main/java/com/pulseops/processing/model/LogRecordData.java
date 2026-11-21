package com.pulseops.processing.model;

import java.time.Instant;
import java.util.Map;

/**
 * A log line ready for storage.
 *
 * @param level one of TRACE, DEBUG, INFO, WARN, ERROR, FATAL (application_logs CHECK)
 */
public record LogRecordData(
        Instant occurredAt,
        String level,
        String message,
        String logger,
        String traceId,
        String spanId,
        Map<String, Object> attributes) {

    public LogRecordData {
        attributes = Map.copyOf(attributes);
    }
}
