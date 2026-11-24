package com.pulseops.incident.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure rules of automatic incident creation (ADR-025): when anomalies justify an incident,
 * its severity, and a title and summary a person can act on.
 */
public final class IncidentRules {

    static final List<String> SEVERITIES = List.of("low", "medium", "high", "critical");

    private IncidentRules() {
    }

    /** The facts about an anomaly that correlation needs. */
    public record AnomalyFact(String serviceName, String metric, String scope, String type, String severity,
                              String reason) {
    }

    public static int rank(String severity) {
        int i = SEVERITIES.indexOf(severity);
        return i < 0 ? 0 : i + 1;
    }

    public static String worst(Collection<String> severities) {
        return severities.stream().max(Comparator.comparingInt(IncidentRules::rank)).orElse("low");
    }

    /**
     * A single low/medium anomaly stays an anomaly; a high/critical one, or two or more
     * together, open an incident. Keeps noise out of the incident list.
     */
    public static boolean justifiesIncident(List<AnomalyFact> anomalies) {
        return anomalies.size() >= 2 || anomalies.stream().anyMatch(a -> rank(a.severity()) >= rank("high"));
    }

    /** What kind of problem this is, in order of urgency. */
    static String family(List<AnomalyFact> anomalies) {
        if (has(anomalies, "error_rate", null)) {
            return "Error rate increase";
        }
        if (has(anomalies, "request_rate", "drop")) {
            return "Traffic drop";
        }
        if (has(anomalies, "response_latency", null) || has(anomalies, "db_duration", null)) {
            return "Latency increase";
        }
        if (has(anomalies, "connection_utilization", null)) {
            return "Connection pool saturation";
        }
        return "Traffic spike";
    }

    public static String title(List<AnomalyFact> anomalies) {
        Set<String> services = new LinkedHashSet<>();
        anomalies.forEach(a -> services.add(a.serviceName()));
        String where = services.size() <= 2 ? String.join(" and ", services) : services.size() + " services";
        String title = family(anomalies) + " on " + where;
        String family = family(anomalies);
        if (!family.equals("Latency increase") && has(anomalies, "db_duration", null)) {
            title += " with slow database";
        } else if (family.equals("Latency increase") && has(anomalies, "db_duration", null)) {
            title += " (database slower)";
        }
        return title.length() <= 300 ? title : title.substring(0, 297) + "...";
    }

    /** One line per anomaly, worst first, prefixed with its scope. */
    public static String summary(List<AnomalyFact> anomalies) {
        List<AnomalyFact> sorted = new ArrayList<>(anomalies);
        sorted.sort(Comparator.comparingInt((AnomalyFact a) -> rank(a.severity())).reversed()
                .thenComparing(AnomalyFact::serviceName).thenComparing(AnomalyFact::scope));
        StringBuilder b = new StringBuilder();
        for (AnomalyFact a : sorted) {
            if (!b.isEmpty()) {
                b.append('\n');
            }
            b.append("- [").append(a.severity()).append("] ").append(a.serviceName());
            if (!"service".equals(a.scope())) {
                b.append(" ").append(a.scope());
            }
            b.append(": ").append(a.reason() == null ? a.metric() + " " + a.type() : a.reason());
        }
        return b.toString();
    }

    private static boolean has(List<AnomalyFact> anomalies, String metric, String type) {
        return anomalies.stream().anyMatch(a -> a.metric().equals(metric) && (type == null || type.equals(a.type())));
    }
}
