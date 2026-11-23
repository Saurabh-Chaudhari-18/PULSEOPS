package com.pulseops.monitoring.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Response shapes of the monitoring API. Latency in milliseconds, rates as 0..1 fractions. */
public final class MonitoringViews {

    private MonitoringViews() {
    }

    public record Window(Instant from, Instant to, int stepSeconds) {
        static Window of(TimeWindow w) {
            return new Window(w.from(), w.to(), w.stepSeconds());
        }
    }

    /** Health derived from the window's numbers with fixed default thresholds (ADR-020). */
    public enum Health { HEALTHY, WARNING, CRITICAL, NO_DATA }

    public record HealthAssessment(Health status, List<String> reasons) {
    }

    public record ServiceSummary(UUID serviceId, String serviceName, String connectionStatus,
                                 Instant lastTelemetryAt, Window window, long requests, Double requestsPerSecond,
                                 long serverErrors, long clientErrors, Double errorRate, Double avgMs,
                                 Double p50Ms, Double p95Ms, Double p99Ms, Double maxMs,
                                 Map<String, Long> statusClasses, HealthAssessment health) {
    }

    public record SeriesPoint(Instant time, long requests, Double requestsPerSecond, long serverErrors,
                              long clientErrors, Double errorRate, Double avgMs, Double p50Ms, Double p95Ms,
                              Double p99Ms) {
    }

    public record TimeSeries(UUID serviceId, String method, String route, Window window, List<SeriesPoint> points) {
    }

    public record EndpointView(String method, String route, long requests, Double requestsPerSecond,
                               long serverErrors, long clientErrors, Double errorRate, Double avgMs, Double p50Ms,
                               Double p95Ms, Double p99Ms, Double maxMs, Instant lastSeen) {
    }

    public record EndpointList(UUID serviceId, Window window, String sort, int totalEndpoints,
                               List<EndpointView> endpoints) {
    }

    public record ServiceOverview(UUID serviceId, String serviceName, UUID projectId, String projectName,
                                  String connectionStatus, Instant lastTelemetryAt, long requests,
                                  Double requestsPerSecond, Double errorRate, Double p95Ms,
                                  HealthAssessment health) {
    }

    public record OrganizationOverview(Window window, int services, int connectedServices, long requests,
                                       Double requestsPerSecond, Double errorRate, Double p95Ms,
                                       Map<Health, Long> servicesByHealth, List<ServiceOverview> attention,
                                       List<ServiceOverview> allServices) {
    }

    public record RuntimePoint(Instant time, Double cpuUtilization, Double heapUsedBytes, Double heapLimitBytes,
                               Double threads, Double loadedClasses) {
    }

    public record RuntimeMetrics(UUID serviceId, Window window, boolean available, List<String> metricNames,
                                 List<RuntimePoint> points) {
    }
}
