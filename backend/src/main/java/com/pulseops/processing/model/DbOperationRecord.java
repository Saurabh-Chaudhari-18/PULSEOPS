package com.pulseops.processing.model;

import java.time.Instant;

/**
 * A database call observed in a customer application.
 *
 * @param operationType SELECT, INSERT, UPDATE, DELETE, DDL, CALL or OTHER
 * @param operation     statement shape with literals replaced by '?', never parameters
 */
public record DbOperationRecord(
        String dbSystem,
        String operationType,
        String operation,
        String tableName,
        double durationMs,
        Instant occurredAt,
        boolean error,
        String errorMessage,
        String traceId,
        String spanId) {
}
