package com.pulseops.monitoring.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.pulseops.monitoring.service.MonitoringViews.Health;
import com.pulseops.monitoring.service.MonitoringViews.HealthAssessment;

/**
 * Default health classification for a time window (ADR-020). These are fixed, documented
 * thresholds — the starting point the specification gives for alerting (error rate above
 * 5 %, p95 above 1000 ms) — not anomaly detection, which compares against learned
 * baselines in a later phase. Fewer than {@link #MIN_REQUESTS} requests are not judged.
 */
public final class HealthRules {

    public static final int MIN_REQUESTS = 20;
    public static final double ERROR_RATE_CRITICAL = 0.05;
    public static final double ERROR_RATE_WARNING = 0.01;
    public static final double P95_CRITICAL_MS = 1_000;
    public static final double P95_WARNING_MS = 500;

    private HealthRules() {
    }

    public static HealthAssessment assess(LatencyStats stats) {
        if (stats.requests() == 0) {
            return new HealthAssessment(Health.NO_DATA, List.of("No requests recorded in this time range"));
        }
        if (stats.requests() < MIN_REQUESTS) {
            return new HealthAssessment(Health.HEALTHY,
                    List.of("Only " + stats.requests() + " requests; too few to judge error rate or latency"));
        }
        Health health = Health.HEALTHY;
        List<String> reasons = new ArrayList<>();
        double errorRate = (double) stats.serverErrors() / stats.requests();
        if (errorRate >= ERROR_RATE_CRITICAL) {
            health = Health.CRITICAL;
            reasons.add("Server error rate " + percent(errorRate) + " is at or above " + percent(ERROR_RATE_CRITICAL));
        } else if (errorRate >= ERROR_RATE_WARNING) {
            health = Health.WARNING;
            reasons.add("Server error rate " + percent(errorRate) + " is at or above " + percent(ERROR_RATE_WARNING));
        }
        Double p95 = stats.p95Ms();
        if (p95 != null && p95 >= P95_CRITICAL_MS) {
            health = Health.CRITICAL;
            reasons.add("p95 latency " + ms(p95) + " is at or above " + ms(P95_CRITICAL_MS));
        } else if (p95 != null && p95 >= P95_WARNING_MS) {
            health = health == Health.CRITICAL ? health : Health.WARNING;
            reasons.add("p95 latency " + ms(p95) + " is at or above " + ms(P95_WARNING_MS));
        }
        return new HealthAssessment(health, List.copyOf(reasons));
    }

    static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100);
    }

    static String ms(double value) {
        return String.format(Locale.ROOT, "%.0f ms", value);
    }
}
