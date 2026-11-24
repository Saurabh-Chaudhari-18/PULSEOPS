package com.pulseops.incident.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseops.incident.service.IncidentRules.AnomalyFact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Groups open anomalies into incidents (ADR-025). Runs after each anomaly detection pass for
 * an organization, under that organization's tenant context:
 * <ol>
 *   <li>New anomalies of a project join the project's active automatic incident; if there is
 *       none, they open one when they justify it ({@link IncidentRules#justifiesIncident}).</li>
 *   <li>Severity follows the worst linked anomaly; the generated summary stays current while
 *       nobody has started working on the incident.</li>
 *   <li>When every linked anomaly has ended, an untouched incident resolves itself; one a
 *       person is working on only gets a timeline entry.</li>
 * </ol>
 * Every change is recorded in the incident timeline with a system actor.
 */
@Component
public class IncidentCorrelator {

    private static final Logger log = LoggerFactory.getLogger(IncidentCorrelator.class);
    static final String AUTO_RESOLVED_NOTE = "Automatically resolved: all related anomalies recovered.";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public IncidentCorrelator(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.objectMapper = objectMapper;
    }

    /** Result counts of one pass. */
    public record Result(int incidentsCreated, int anomaliesLinked, int incidentsRecovered) {
    }

    record Unlinked(UUID id, UUID projectId, UUID serviceId, AnomalyFact fact) {
    }

    /** Must be called with the organization's tenant context bound. */
    public Result correlate(UUID orgId) {
        int[] counts = tx.execute(status -> {
            int created = 0;
            int linked = 0;
            Map<UUID, List<Unlinked>> byProject = new LinkedHashMap<>();
            jdbc.query(IncidentSql.UNLINKED_ANOMALIES, rs -> {
                Unlinked u = new Unlinked(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                        rs.getObject("service_id", UUID.class), new AnomalyFact(rs.getString("service_name"),
                        rs.getString("metric"), rs.getString("scope"), rs.getString("anomaly_type"),
                        rs.getString("severity"), rs.getString("reason")));
                byProject.computeIfAbsent(u.projectId(), k -> new ArrayList<>()).add(u);
            }, orgId);

            for (Map.Entry<UUID, List<Unlinked>> entry : byProject.entrySet()) {
                UUID projectId = entry.getKey();
                List<Unlinked> fresh = entry.getValue();
                String dedupKey = "auto:project:" + projectId;
                List<UUID> active = jdbc.query(IncidentSql.ACTIVE_BY_DEDUP, (rs, n) -> rs.getObject("id", UUID.class),
                        orgId, dedupKey);
                UUID incidentId = active.isEmpty() ? null : active.get(0);
                if (incidentId == null) {
                    List<AnomalyFact> facts = fresh.stream().map(Unlinked::fact).toList();
                    if (!IncidentRules.justifiesIncident(facts)) {
                        continue;
                    }
                    List<UUID> inserted = jdbc.query(IncidentSql.CREATE_AUTOMATIC,
                            (rs, n) -> rs.getObject("id", UUID.class), orgId, projectId, singleService(fresh),
                            IncidentRules.title(facts), IncidentRules.summary(facts),
                            IncidentRules.worst(facts.stream().map(AnomalyFact::severity).toList()), dedupKey);
                    if (inserted.isEmpty()) {
                        continue;   // created concurrently; the next pass links to it
                    }
                    incidentId = inserted.get(0);
                    created++;
                    event(orgId, incidentId, "created", null, null, "open",
                            Map.of("source", "automatic", "anomalies", fresh.size()));
                }
                for (Unlinked u : fresh) {
                    if (jdbc.update(IncidentSql.LINK, orgId, incidentId, u.id()) == 1) {
                        jdbc.update(IncidentSql.MARK_LINKED, orgId, u.id());
                        linked++;
                        event(orgId, incidentId, "anomaly_linked", null, null, u.fact().severity(),
                                Map.of("anomalyId", u.id().toString(), "service", u.fact().serviceName(),
                                        "metric", u.fact().metric(), "scope", u.fact().scope()));
                    }
                }
                refresh(orgId, incidentId);
            }
            return new int[]{created, linked};
        });
        Result result = new Result(counts == null ? 0 : counts[0], counts == null ? 0 : counts[1], recover(orgId));
        if (result.incidentsCreated() > 0 || result.incidentsRecovered() > 0) {
            log.info("Correlation for organization {}: {} incident(s) created, {} anomaly link(s), {} recovered",
                    orgId, result.incidentsCreated(), result.anomaliesLinked(), result.incidentsRecovered());
        }
        return result;
    }

    /** Severity up to the worst linked anomaly; title/summary/scope regenerated while untouched. */
    private void refresh(UUID orgId, UUID incidentId) {
        List<AnomalyFact> facts = new ArrayList<>();
        Set<UUID> services = new LinkedHashSet<>();
        jdbc.query(IncidentSql.LINKED_FACTS, rs -> {
            facts.add(new AnomalyFact(rs.getString("service_name"), rs.getString("metric"), rs.getString("scope"),
                    rs.getString("anomaly_type"), rs.getString("severity"), rs.getString("reason")));
            services.add(rs.getObject("service_id", UUID.class));
        }, incidentId);
        String worst = IncidentRules.worst(facts.stream().map(AnomalyFact::severity).toList());
        List<String> current = jdbc.query(IncidentSql.SEVERITY_OF, (rs, n) -> rs.getString("severity"), orgId,
                incidentId);
        if (current.isEmpty()) {
            return;
        }
        String severity = current.get(0);
        if (IncidentRules.rank(worst) > IncidentRules.rank(severity)) {
            jdbc.update(IncidentSql.SET_SEVERITY, worst, incidentId, orgId);
            event(orgId, incidentId, "severity_changed", null, severity, worst, Map.of("reason", "worse anomaly linked"));
        }
        jdbc.update(IncidentSql.REFRESH_AUTOMATIC, IncidentRules.title(facts), IncidentRules.summary(facts),
                services.size() == 1 ? services.iterator().next() : null, incidentId, orgId);
    }

    private int recover(UUID orgId) {
        Integer recovered = tx.execute(status -> {
            int count = 0;
            for (String[] row : jdbc.query(IncidentSql.RECOVERED,
                    (rs, n) -> new String[]{rs.getString("id"), rs.getString("status")}, orgId)) {
                UUID id = UUID.fromString(row[0]);
                event(orgId, id, "anomalies_recovered", null, null, null, Map.of());
                if ("open".equals(row[1]) && jdbc.update(IncidentSql.AUTO_RESOLVE, AUTO_RESOLVED_NOTE, id, orgId) == 1) {
                    event(orgId, id, "status_changed", null, "open", "resolved", Map.of("reason", AUTO_RESOLVED_NOTE));
                }
                count++;
            }
            return count;
        });
        return recovered == null ? 0 : recovered;
    }

    private static UUID singleService(List<Unlinked> anomalies) {
        Set<UUID> services = new LinkedHashSet<>();
        anomalies.forEach(a -> services.add(a.serviceId()));
        return services.size() == 1 ? services.iterator().next() : null;
    }

    private void event(UUID orgId, UUID incidentId, String type, UUID actor, String oldValue, String newValue,
                       Map<String, ?> details) {
        String json;
        try {
            json = objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        jdbc.update(IncidentSql.EVENT, orgId, incidentId, type, actor, oldValue, newValue, json);
    }
}
