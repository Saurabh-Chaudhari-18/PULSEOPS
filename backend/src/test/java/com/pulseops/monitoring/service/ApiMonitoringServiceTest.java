package com.pulseops.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pulseops.common.error.ApiException;
import com.pulseops.monitoring.service.MonitoringViews.EndpointView;
import com.pulseops.monitoring.service.MonitoringViews.SeriesPoint;

import org.junit.jupiter.api.Test;

/** Protocol-free parts of the monitoring read model; the SQL is exercised by the journey test. */
class ApiMonitoringServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T10:30:00Z"), ZoneOffset.UTC);

    private static EndpointView endpoint(String route, long requests, long errors, Double p95) {
        return new EndpointView("GET", route, requests, null, errors, 0,
                requests == 0 ? null : (double) errors / requests, 10.0, 5.0, p95, p95, p95, null);
    }

    @Test
    void seriesHasEveryPointAndEmptyBucketsHaveNoLatency() {
        TimeWindow window = TimeWindow.parse("2026-11-23T10:00:00Z", "2026-11-23T10:05:00Z", 60, CLOCK);
        LatencyStats busy = LatencyStats.of(120, 6, 0, 1200, 30.0, new long[]{0, 0, 0, 120});
        List<SeriesPoint> points = ApiMonitoringService.fill(window,
                Map.of(Instant.parse("2026-11-23T10:02:00Z"), busy));

        assertThat(points).hasSize(5);
        assertThat(points.get(0).requests()).isZero();
        assertThat(points.get(0).p95Ms()).as("no data is not 0 ms").isNull();
        assertThat(points.get(0).errorRate()).isNull();
        SeriesPoint p = points.get(2);
        assertThat(p.time()).isEqualTo(Instant.parse("2026-11-23T10:02:00Z"));
        assertThat(p.requests()).isEqualTo(120);
        assertThat(p.requestsPerSecond()).isEqualTo(2.0);
        assertThat(p.errorRate()).isEqualTo(0.05);
        assertThat(p.avgMs()).isEqualTo(10.0);
    }

    @Test
    void endpointSortsPutTheWorstFirstAndNullsLast() {
        List<EndpointView> endpoints = new ArrayList<>(List.of(
                endpoint("/a", 1000, 0, 20.0),
                endpoint("/b", 10, 5, 900.0),
                endpoint("/c", 500, 50, 300.0),
                endpoint("/d", 0, 0, null)));

        endpoints.sort(ApiMonitoringService.comparator("p95"));
        assertThat(endpoints).extracting(EndpointView::route).containsExactly("/b", "/c", "/a", "/d");
        endpoints.sort(ApiMonitoringService.comparator("error_rate"));
        assertThat(endpoints).extracting(EndpointView::route).containsExactly("/b", "/c", "/a", "/d");
        endpoints.sort(ApiMonitoringService.comparator("errors"));
        assertThat(endpoints).extracting(EndpointView::route).containsExactly("/c", "/b", "/a", "/d");
        endpoints.sort(ApiMonitoringService.comparator("requests"));
        assertThat(endpoints).extracting(EndpointView::route).containsExactly("/a", "/c", "/b", "/d");
        endpoints.sort(ApiMonitoringService.comparator("route"));
        assertThat(endpoints).extracting(EndpointView::route).containsExactly("/a", "/b", "/c", "/d");
    }

    @Test
    void methodIsValidatedForEndpointSeries() {
        assertThat(ApiMonitoringService.normalizeMethod(" get ")).isEqualTo("GET");
        assertThatThrownBy(() -> ApiMonitoringService.normalizeMethod(null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("METHOD_REQUIRED"));
        assertThatThrownBy(() -> ApiMonitoringService.normalizeMethod("BREW"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_METHOD"));
    }

    @Test
    void runtimePointsKeepMissingMetricsNull() {
        TimeWindow window = TimeWindow.parse("2026-11-23T10:00:00Z", "2026-11-23T10:02:00Z", 60, CLOCK);
        var points = RuntimeMetricsService.points(window, Map.of(Instant.parse("2026-11-23T10:00:00Z"),
                Map.of(MonitoringSql.CPU, 0.23456, MonitoringSql.MEMORY_USED, 150_000_000.4)));
        assertThat(points).hasSize(2);
        assertThat(points.get(0).cpuUtilization()).isEqualTo(0.2346);
        assertThat(points.get(0).heapUsedBytes()).isEqualTo(150_000_000.0);
        assertThat(points.get(0).threads()).isNull();
        assertThat(points.get(1).cpuUtilization()).isNull();
    }
}
