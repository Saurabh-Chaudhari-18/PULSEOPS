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

    /** Thresholds and wording for one kind of traffic. */
    public record Rules(String unit, String errorLabel, double errorWarning, double errorCritical,
                        double p95WarningMs, double p95CriticalMs) {
    }

    /** HTTP requests: 5xx rate and p95 latency (the specification's alerting examples). */
    public static final Rules HTTP = new Rules("requests", "Server error rate", ERROR_RATE_WARNING,
            ERROR_RATE_CRITICAL, P95_WARNING_MS, P95_CRITICAL_MS);

    /**
     * Database calls: same error thresholds; a single query is expected to be much faster
     * than a whole request, so p95 warns at 200 ms and is critical at 1000 ms.
     */
    public static final Rules DATABASE = new Rules("database calls", "Database error rate", 0.01, 0.05, 200,
            1_000);

    private HealthRules() {
    }

    public static HealthAssessment assess(LatencyStats stats) {
        return assess(HTTP, stats.requests(), stats.serverErrors(), stats.p95Ms());
    }

    public static HealthAssessment assess(Rules rules, long count, long errors, Double p95) {
        if (count == 0) {
            return new HealthAssessment(Health.NO_DATA, List.of("No " + rules.unit() + " recorded in this time range"));
        }
        if (count < MIN_REQUESTS) {
            return new HealthAssessment(Health.HEALTHY,
                    List.of("Only " + count + " " + rules.unit() + "; too few to judge error rate or latency"));
        }
        Health health = Health.HEALTHY;
        List<String> reasons = new ArrayList<>();
        double errorRate = (double) errors / count;
        if (errorRate >= rules.errorCritical()) {
            health = Health.CRITICAL;
            reasons.add(rules.errorLabel() + " " + percent(errorRate) + " is at or above "
                    + percent(rules.errorCritical()));
        } else if (errorRate >= rules.errorWarning()) {
            health = Health.WARNING;
            reasons.add(rules.errorLabel() + " " + percent(errorRate) + " is at or above "
                    + percent(rules.errorWarning()));
        }
        if (p95 != null && p95 >= rules.p95CriticalMs()) {
            health = Health.CRITICAL;
            reasons.add("p95 latency " + ms(p95) + " is at or above " + ms(rules.p95CriticalMs()));
        } else if (p95 != null && p95 >= rules.p95WarningMs()) {
            health = health == Health.CRITICAL ? health : Health.WARNING;
            reasons.add("p95 latency " + ms(p95) + " is at or above " + ms(rules.p95WarningMs()));
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
