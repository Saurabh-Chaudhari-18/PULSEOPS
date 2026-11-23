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
import com.pulseops.monitoring.service.DatabaseViews.DbSeriesPoint;
import com.pulseops.monitoring.service.DatabaseViews.OperationView;
import com.pulseops.monitoring.service.DatabaseViews.PoolPoint;

import org.junit.jupiter.api.Test;

/** Protocol-free parts of database monitoring; the SQL is exercised by the journey test. */
class DatabaseMonitoringServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T10:30:00Z"), ZoneOffset.UTC);

    private static OperationView op(String sql, long calls, long errors, long slow, double totalMs, Double p95) {
        return new OperationView("postgresql", "SELECT", sql, "orders", calls, null, errors,
                calls == 0 ? null : (double) errors / calls, slow, totalMs, null, totalMs / calls, p95, p95, p95, p95,
                null, null);
    }

    @Test
    void operationSortsPutTheCostliestFirst() {
        List<OperationView> ops = new ArrayList<>(List.of(
                op("frequent", 10_000, 0, 0, 20_000, 3.0),
                op("slow", 10, 0, 10, 15_000, 1_600.0),
                op("failing", 100, 40, 0, 500, 8.0)));

        ops.sort(DatabaseMonitoringService.comparator("total_time"));
        assertThat(ops).extracting(OperationView::operation).containsExactly("frequent", "slow", "failing");
        ops.sort(DatabaseMonitoringService.comparator("p95"));
        assertThat(ops).extracting(OperationView::operation).containsExactly("slow", "failing", "frequent");
        ops.sort(DatabaseMonitoringService.comparator("calls"));
        assertThat(ops).extracting(OperationView::operation).containsExactly("frequent", "failing", "slow");
        ops.sort(DatabaseMonitoringService.comparator("errors"));
        assertThat(ops.get(0).operation()).isEqualTo("failing");
        ops.sort(DatabaseMonitoringService.comparator("slow"));
        assertThat(ops.get(0).operation()).isEqualTo("slow");
    }

    @Test
    void seriesAndPoolPointsKeepAbsentDataNull() {
        TimeWindow window = TimeWindow.parse("2026-11-23T10:00:00Z", "2026-11-23T10:02:00Z", 60, CLOCK);
        List<DbSeriesPoint> series = DatabaseMonitoringService.fill(window, Map.of(
                Instant.parse("2026-11-23T10:01:00Z"),
                new DbStats(120, 2, 1, 600, 5.0, 4.0, 9.0, 12.0, 600.0)));
        assertThat(series).hasSize(2);
        assertThat(series.get(0).calls()).isZero();
        assertThat(series.get(0).p95Ms()).isNull();
        assertThat(series.get(1).callsPerSecond()).isEqualTo(2.0);
        assertThat(series.get(1).slowCalls()).isEqualTo(1);

        List<PoolPoint> pool = DatabaseMonitoringService.poolPoints(window, Map.of(
                Instant.parse("2026-11-23T10:00:00Z"),
                Map.of("used", new double[]{4, 6}, "max", new double[]{10, 10})));
        assertThat(pool.get(0).used()).isEqualTo(4.0);
        assertThat(pool.get(0).peakUsed()).isEqualTo(6.0);
        assertThat(pool.get(0).max()).isEqualTo(10.0);
        assertThat(pool.get(0).idle()).isNull();
        assertThat(pool.get(1).used()).isNull();
    }

    @Test
    void thresholdsAndSharesAreValidated() {
        assertThat(DatabaseMonitoringService.slowThreshold(null)).isEqualTo(500.0);
        assertThat(DatabaseMonitoringService.slowThreshold(50.0)).isEqualTo(50.0);
        assertThatThrownBy(() -> DatabaseMonitoringService.slowThreshold(0.0))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_THRESHOLD"));
        assertThat(DatabaseMonitoringService.share(25, 100)).isEqualTo(0.25);
        assertThat(DatabaseMonitoringService.share(0, 0)).isNull();
    }
}
