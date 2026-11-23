package com.pulseops.monitoring.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.pulseops.monitoring.service.MonitoringViews.HealthAssessment;
import com.pulseops.monitoring.service.MonitoringViews.Window;

/** Response shapes of the database monitoring API. Durations in ms, rates as 0..1 fractions. */
public final class DatabaseViews {

    private DatabaseViews() {
    }

    public record Breakdown(String dbSystem, String operationType, long calls, long errors, double totalMs,
                            Double shareOfTime) {
    }

    public record DatabaseSummary(UUID serviceId, Window window, double slowThresholdMs, long calls,
                                  Double callsPerSecond, long errors, Double errorRate, long slowCalls,
                                  double totalMs, Double avgMs, Double p50Ms, Double p95Ms, Double p99Ms,
                                  Double maxMs, List<Breakdown> breakdown, HealthAssessment health) {
    }

    /**
     * One query shape (statement with literals removed at ingestion). {@code slowestTraceId}
     * links the slowest call to its trace.
     */
    public record OperationView(String dbSystem, String operationType, String operation, String table,
                                long calls, Double callsPerSecond, long errors, Double errorRate, long slowCalls,
                                double totalMs, Double shareOfTime, Double avgMs, Double p50Ms, Double p95Ms,
                                Double p99Ms, Double maxMs, Instant lastSeen, String slowestTraceId) {
    }

    public record OperationList(UUID serviceId, Window window, String sort, double slowThresholdMs,
                                int totalOperations, List<OperationView> operations) {
    }

    public record DbSeriesPoint(Instant time, long calls, Double callsPerSecond, long errors, long slowCalls,
                                Double avgMs, Double p50Ms, Double p95Ms, Double p99Ms) {
    }

    public record DbSeries(UUID serviceId, String operation, Window window, double slowThresholdMs,
                           List<DbSeriesPoint> points) {
    }

    public record CallView(Instant occurredAt, String dbSystem, String operationType, String operation,
                           String table, double durationMs, boolean error, String errorMessage, String traceId,
                           String spanId) {
    }

    public record CallList(UUID serviceId, Window window, double minDurationMs, boolean errorsOnly,
                           List<CallView> calls) {
    }

    public record PoolPoint(Instant time, Double used, Double idle, Double max, Double pending, Double peakUsed) {
    }

    /** Connection pool gauges when the service reports them (e.g. HikariCP via the Java agent). */
    public record ConnectionPool(UUID serviceId, Window window, boolean available, List<String> pools,
                                 List<PoolPoint> points) {
    }
}
