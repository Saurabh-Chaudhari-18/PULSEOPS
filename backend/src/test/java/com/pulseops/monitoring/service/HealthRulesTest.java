package com.pulseops.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseops.monitoring.service.MonitoringViews.Health;
import com.pulseops.monitoring.service.MonitoringViews.HealthAssessment;

import org.junit.jupiter.api.Test;

class HealthRulesTest {

    private static LatencyStats stats(long requests, long errors, Double p95) {
        return new LatencyStats(requests, errors, 0, 10.0, 5.0, p95, p95, p95);
    }

    @Test
    void classifiesByErrorRateAndP95() {
        assertThat((Object) HealthRules.assess(LatencyStats.EMPTY).status()).isEqualTo(Health.NO_DATA);
        assertThat((Object) HealthRules.assess(stats(10, 10, 5000.0)).status())
                .as("too few requests to judge").isEqualTo(Health.HEALTHY);
        assertThat((Object) HealthRules.assess(stats(1000, 5, 100.0)).status()).isEqualTo(Health.HEALTHY);
        assertThat((Object) HealthRules.assess(stats(1000, 10, 100.0)).status()).isEqualTo(Health.WARNING);
        assertThat((Object) HealthRules.assess(stats(1000, 50, 100.0)).status()).isEqualTo(Health.CRITICAL);
        assertThat((Object) HealthRules.assess(stats(1000, 0, 500.0)).status()).isEqualTo(Health.WARNING);
        assertThat((Object) HealthRules.assess(stats(1000, 0, 1500.0)).status()).isEqualTo(Health.CRITICAL);
    }

    @Test
    void reasonsExplainEveryBreach() {
        HealthAssessment both = HealthRules.assess(stats(200, 20, 1200.0));
        assertThat((Object) both.status()).isEqualTo(Health.CRITICAL);
        assertThat((Object) both.reasons().size()).isEqualTo(2);
        assertThat((Object) both.reasons().get(0)).isEqualTo("Server error rate 10.0% is at or above 5.0%");
        assertThat((Object) both.reasons().get(1)).isEqualTo("p95 latency 1200 ms is at or above 1000 ms");
        HealthAssessment criticalErrorsWarningLatency = HealthRules.assess(stats(200, 20, 600.0));
        assertThat((Object) criticalErrorsWarningLatency.status()).as("worst wins").isEqualTo(Health.CRITICAL);
    }

    @Test
    void latencyStatsComeFromTheMergedHistogram() {
        // 100 requests: 20 in (2,5], 40 in (5,10], 40 in (10,25] ms; one 5xx-free window.
        LatencyStats s = LatencyStats.of(100, 0, 10, 1200.0, 24.0, new long[]{0, 0, 20, 40, 40});
        assertThat((Object) s.avgMs()).isEqualTo(12.0);
        assertThat((Object) s.p50Ms()).as("rank 50 lies 30/40 into (5,10]").isEqualTo(8.75);
        assertThat((Object) s.p95Ms()).as("rank 95 lies 35/40 into (10,25]").isEqualTo(23.13);
        assertThat((Object) s.p99Ms()).as("capped at observed max").isEqualTo(24.0);
        assertThat((Object) s.errorRate()).isEqualTo(0.0);
        assertThat((Object) LatencyStats.of(0, 0, 0, 0, null, new long[0]).p95Ms()).isNull();
    }
}
