package com.pulseops.incident.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.pulseops.common.error.ApiException;
import com.pulseops.incident.service.IncidentRules.AnomalyFact;

import org.junit.jupiter.api.Test;

class IncidentRulesTest {

    private static AnomalyFact fact(String service, String metric, String scope, String type, String severity) {
        return new AnomalyFact(service, metric, scope, type, severity, metric + " reason");
    }

    private static String failure(Runnable action) {
        try {
            action.run();
            return null;
        } catch (ApiException e) {
            return e.status().name() + " " + e.code();
        }
    }

    @Test
    void ranksSeveritiesAndPicksTheWorst() {
        assertThat(IncidentRules.rank("low")).isEqualTo(1);
        assertThat(IncidentRules.rank("critical")).isEqualTo(4);
        assertThat(IncidentRules.rank("urgent")).isEqualTo(0);
        assertThat(IncidentRules.worst(List.of("medium", "critical", "high"))).isEqualTo("critical");
        assertThat(IncidentRules.worst(List.of())).isEqualTo("low");
    }

    @Test
    void aSingleMinorAnomalyDoesNotOpenAnIncident() {
        assertThat(IncidentRules.justifiesIncident(List.of(fact("orders", "request_rate", "service", "spike", "medium"))))
                .isEqualTo(false);
        assertThat(IncidentRules.justifiesIncident(List.of(fact("orders", "response_latency", "service", "spike", "high"))))
                .isEqualTo(true);
        assertThat(IncidentRules.justifiesIncident(List.of(fact("orders", "request_rate", "service", "spike", "low"),
                fact("orders", "error_rate", "service", "spike", "low")))).isEqualTo(true);
        assertThat(IncidentRules.justifiesIncident(List.of())).isEqualTo(false);
    }

    @Test
    void titleNamesTheMostUrgentFamilyAndTheServices() {
        List<AnomalyFact> slowDb = List.of(fact("order-service", "response_latency", "GET /api/orders/{id}", "spike", "critical"),
                fact("order-service", "db_duration", "service", "spike", "critical"));
        assertThat(IncidentRules.title(slowDb)).isEqualTo("Latency increase on order-service (database slower)");

        List<AnomalyFact> errors = List.of(fact("orders", "response_latency", "service", "spike", "high"),
                fact("payments", "error_rate", "service", "spike", "critical"),
                fact("payments", "db_duration", "service", "spike", "high"));
        assertThat(IncidentRules.title(errors)).isEqualTo("Error rate increase on orders and payments with slow database");

        List<AnomalyFact> drop = List.of(fact("a", "request_rate", "service", "drop", "high"),
                fact("b", "request_rate", "service", "spike", "high"), fact("c", "request_rate", "service", "spike", "high"));
        assertThat(IncidentRules.title(drop)).isEqualTo("Traffic drop on 3 services");

        assertThat(IncidentRules.title(List.of(fact("a", "connection_utilization", "service", "spike", "high"))))
                .isEqualTo("Connection pool saturation on a");
        assertThat(IncidentRules.title(List.of(fact("a", "request_rate", "service", "spike", "high"))))
                .isEqualTo("Traffic spike on a");
    }

    @Test
    void summaryListsWorstFirstWithScope() {
        String summary = IncidentRules.summary(List.of(fact("orders", "request_rate", "service", "spike", "medium"),
                new AnomalyFact("orders", "response_latency", "GET /a", "spike", "critical", "p95 1500 ms"),
                new AnomalyFact("orders", "error_rate", "service", "spike", "high", null)));
        assertThat(summary).isEqualTo("- [critical] orders GET /a: p95 1500 ms\n"
                + "- [high] orders: error_rate spike\n"
                + "- [medium] orders: request_rate reason");
    }

    @Test
    void transitionsFollowTheLifecycle() {
        assertThat(failure(() -> IncidentTransitions.check("open", "acknowledged"))).isNull();
        assertThat(failure(() -> IncidentTransitions.check("open", "resolved"))).isNull();
        assertThat(failure(() -> IncidentTransitions.check("acknowledged", "investigating"))).isNull();
        assertThat(failure(() -> IncidentTransitions.check("resolved", "open"))).isNull();
        assertThat(failure(() -> IncidentTransitions.check("resolved", "closed"))).isNull();
        assertThat(failure(() -> IncidentTransitions.check("open", "closed"))).isEqualTo("CONFLICT INVALID_TRANSITION");
        assertThat(failure(() -> IncidentTransitions.check("investigating", "open"))).isEqualTo("CONFLICT INVALID_TRANSITION");
        assertThat(failure(() -> IncidentTransitions.check("closed", "open"))).isEqualTo("CONFLICT INVALID_TRANSITION");
        assertThat(failure(() -> IncidentTransitions.check("open", "done"))).isEqualTo("BAD_REQUEST INVALID_STATUS");
        assertThat(IncidentTransitions.isActive("acknowledged")).isEqualTo(true);
        assertThat(IncidentTransitions.isActive("resolved")).isEqualTo(false);
        assertThat(IncidentTransitions.isActive("closed")).isEqualTo(false);
    }
}
