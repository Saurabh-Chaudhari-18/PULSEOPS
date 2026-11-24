package com.pulseops.anomaly.service;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseops.anomaly.service.SeriesBuilder.Built;
import com.pulseops.anomaly.service.SeriesBuilder.RequestMinute;
import com.pulseops.anomaly.service.SeriesBuilder.SeriesRef;
import com.pulseops.common.config.PulseOpsProperties;
import com.pulseops.common.tenancy.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * Every minute: find services receiving telemetry, build their per-minute series (service and
 * busiest endpoints: request rate, p95, error rate; database p95; pool utilisation), ask the
 * ML service for findings and store them as anomalies (ADR-024). Each organization is
 * processed under its own tenant context; only the discovery query uses app.discovery.
 */
@Component
public class AnomalyDetectionJob {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionJob.class);
    static final int TOP_ENDPOINTS = 10;
    static final int MAX_SERIES_PER_REQUEST = 200;
    static final Duration ACTIVE_WITHIN = Duration.ofMinutes(15);
    /** Telemetry for a minute keeps arriving for a few seconds after it ends. */
    static final Duration EXPORT_LAG = Duration.ofSeconds(20);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MlClient ml;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PulseOpsProperties.Ml settings;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.NEVER_RUN);

    public AnomalyDetectionJob(JdbcTemplate jdbc, TransactionTemplate tx, MlClient ml, ObjectMapper objectMapper,
                               Clock clock, PulseOpsProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.ml = ml;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.settings = props.ml();
    }

    /**
     * Outcome of the latest run; contains no tenant data. {@code skipReasons} counts series the
     * ML service declined to judge, by reason (e.g. "insufficient history"), so "no findings"
     * can be told apart from "nothing could be judged yet".
     */
    public record Status(boolean enabled, Instant lastRunAt, Instant lastSuccessAt, String lastError,
                         int servicesEvaluated, int seriesEvaluated, int seriesSkipped, Map<String, Integer> skipReasons,
                         int anomaliesFound) {
        static final Status NEVER_RUN = new Status(true, null, null, null, 0, 0, 0, Map.of(), 0);
    }

    public Status status() {
        Status s = status.get();
        return new Status(settings.detectionEnabled(), s.lastRunAt(), s.lastSuccessAt(), s.lastError(),
                s.servicesEvaluated(), s.seriesEvaluated(), s.seriesSkipped(), s.skipReasons(), s.anomaliesFound());
    }

    @Scheduled(initialDelayString = "${pulseops.ml.detection-interval:60s}",
            fixedDelayString = "${pulseops.ml.detection-interval:60s}")
    public void scheduledRun() {
        if (settings.detectionEnabled()) {
            runOnce();
        }
    }

    /** One detection pass over every active service. Never throws. */
    public Status runOnce() {
        return runOnce(clock.instant());
    }

    /**
     * One pass evaluating the minutes up to {@code asOf} (tests, manual re-runs). Detection and
     * resolution timestamps always use the real clock.
     */
    public Status runOnce(Instant asOf) {
        Instant now = clock.instant();
        Instant end = evaluationEnd(asOf);
        Instant from = end.minus(Duration.ofMinutes(settings.historyMinutes()));
        int services = 0;
        int series = 0;
        int found = 0;
        Map<String, Integer> skips = new TreeMap<>();
        String error = null;
        try {
            Map<UUID, List<UUID>> byOrg = discover(asOf.minus(ACTIVE_WITHIN));
            for (Map.Entry<UUID, List<UUID>> org : byOrg.entrySet()) {
                int[] counts = TenantContext.callInOrganization(org.getKey(),
                        () -> processOrganization(org.getKey(), org.getValue(), from, end, now, skips));
                services += org.getValue().size();
                series += counts[0];
                found += counts[1];
            }
        } catch (RestClientException e) {
            error = "ML service unavailable: " + e.getMessage();
            log.warn("Anomaly detection skipped: {}", error);
        } catch (RuntimeException e) {
            error = "Detection run failed: " + e.getClass().getSimpleName();
            log.error("Anomaly detection run failed", e);
        }
        Status previous = status.get();
        int skipped = skips.values().stream().mapToInt(Integer::intValue).sum();
        Status next = new Status(settings.detectionEnabled(), now, error == null ? now : previous.lastSuccessAt(),
                error, services, series, skipped, Map.copyOf(skips), found);
        status.set(next);
        return next;
    }

    /** Exclusive end of the newest complete minute whose telemetry has had time to arrive. */
    static Instant evaluationEnd(Instant now) {
        long seconds = now.minus(EXPORT_LAG).getEpochSecond();
        return Instant.ofEpochSecond(Math.floorDiv(seconds, 60) * 60);
    }

    Map<UUID, List<UUID>> discover(Instant since) {
        return tx.execute(s -> {
            jdbc.queryForObject("SELECT set_config('app.discovery', 'on', true)", String.class);
            Map<UUID, List<UUID>> out = new LinkedHashMap<>();
            jdbc.query(AnomalySql.DISCOVER, rs -> {
                out.computeIfAbsent(rs.getObject("organization_id", UUID.class), k -> new ArrayList<>())
                        .add(rs.getObject("service_id", UUID.class));
            }, Timestamp.from(since));
            return out;
        });
    }

    /** @return {series evaluated, anomalies found} */
    int[] processOrganization(UUID orgId, List<UUID> serviceIds, Instant from, Instant to, Instant now,
                              Map<String, Integer> skips) {
        List<Instant> minutes = SeriesBuilder.minutes(from, to);
        Map<UUID, UUID> projects = new HashMap<>();
        List<Built> all = new ArrayList<>();
        for (UUID serviceId : serviceIds) {
            List<UUID> project = jdbc.query(AnomalySql.SERVICE, (rs, n) -> rs.getObject("project_id", UUID.class),
                    orgId, serviceId);
            if (project.isEmpty()) {
                continue;   // archived or deleted since discovery
            }
            projects.put(serviceId, project.get(0));
            all.addAll(seriesFor(orgId, serviceId, minutes, from, to));
        }
        int found = 0;
        for (int i = 0; i < all.size(); i += MAX_SERIES_PER_REQUEST) {
            List<Built> chunk = all.subList(i, Math.min(all.size(), i + MAX_SERIES_PER_REQUEST));
            Map<String, SeriesRef> refs = new HashMap<>();
            chunk.forEach(b -> refs.put(b.ref().key(), b.ref()));
            MlClient.DetectResponse response = ml.detect(new MlClient.DetectRequest(
                    chunk.stream().map(Built::series).toList(), settings.evaluationMinutes(), settings.sensitivity()));
            if (response == null) {
                continue;
            }
            if (response.skipped() != null) {
                response.skipped().forEach(k -> skips.merge(skipCategory(k.reason()), 1, Integer::sum));
            }
            if (response.anomalies() == null) {
                continue;
            }
            for (MlClient.Finding f : response.anomalies()) {
                SeriesRef ref = refs.get(f.key());
                if (ref != null) {
                    store(orgId, projects.get(ref.serviceId()), ref, f, now);
                    found++;
                }
            }
        }
        Instant cutoff = now.minus(settings.resolveAfter());
        for (UUID serviceId : projects.keySet()) {
            tx.executeWithoutResult(s -> jdbc.update(AnomalySql.RESOLVE_STALE, orgId, serviceId,
                    Timestamp.from(cutoff)));
        }
        return new int[]{all.size(), found};
    }

    List<Built> seriesFor(UUID orgId, UUID serviceId, List<Instant> minutes, Instant from, Instant to) {
        Timestamp f = Timestamp.from(from);
        Timestamp t = Timestamp.from(to);
        List<Built> out = new ArrayList<>();

        Map<Instant, RequestMinute> service = new HashMap<>();
        jdbc.query(AnomalySql.API_SERVICE_MINUTES, rs -> {
            service.put(rs.getTimestamp("t").toInstant(), requestMinute(rs));
        }, orgId, serviceId, f, t);
        out.addAll(SeriesBuilder.requests(serviceId, SeriesBuilder.SERVICE_SCOPE, minutes, service));

        Map<String, Map<Instant, RequestMinute>> endpoints = new LinkedHashMap<>();
        jdbc.query(AnomalySql.API_ENDPOINT_MINUTES, rs -> {
            String scope = rs.getString("http_method") + " " + rs.getString("route_template");
            endpoints.computeIfAbsent(scope, k -> new HashMap<>())
                    .put(rs.getTimestamp("t").toInstant(), requestMinute(rs));
        }, orgId, serviceId, f, t, TOP_ENDPOINTS, orgId, serviceId, f, t);
        endpoints.forEach((scope, data) -> out.addAll(SeriesBuilder.requests(serviceId, truncate(scope), minutes,
                data)));

        Map<Instant, double[]> db = new HashMap<>();
        jdbc.query(AnomalySql.DB_MINUTES, rs -> {
            db.put(rs.getTimestamp("t").toInstant(), new double[]{rs.getLong("calls"), rs.getDouble("p95")});
        }, orgId, serviceId, f, t);
        out.addAll(SeriesBuilder.database(serviceId, minutes, db));

        Map<Instant, double[]> pool = new HashMap<>();
        jdbc.query(AnomalySql.POOL_MINUTES, rs -> {
            double used = rs.getDouble("used");
            boolean usedNull = rs.wasNull();
            double max = rs.getDouble("max");
            pool.put(rs.getTimestamp("t").toInstant(), new double[]{usedNull ? Double.NaN : used, rs.wasNull() ? 0 : max});
        }, orgId, serviceId, f, t);
        out.addAll(SeriesBuilder.pool(serviceId, minutes, pool));
        return out;
    }

    private static RequestMinute requestMinute(ResultSet rs) throws SQLException {
        double max = rs.getDouble("max_ms");
        Double maxMs = rs.wasNull() ? null : max;
        Array array = rs.getArray("buckets");
        long[] buckets = new long[0];
        if (array != null) {
            Object[] values = (Object[]) array.getArray();
            buckets = new long[values.length];
            for (int i = 0; i < values.length; i++) {
                buckets[i] = values[i] == null ? 0 : ((Number) values[i]).longValue();
            }
        }
        return new RequestMinute(rs.getLong("requests"), rs.getLong("errors"), maxMs, buckets);
    }

    private void store(UUID orgId, UUID projectId, SeriesRef ref, MlClient.Finding f, Instant now) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("detector", f.detector());
        evidence.put("spread", f.spread());
        evidence.put("baselinePoints", f.baselinePoints());
        evidence.put("evaluationMinutes", settings.evaluationMinutes());
        evidence.put("sensitivity", settings.sensitivity());
        String json;
        try {
            json = objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        String evidenceJson = json;
        tx.executeWithoutResult(s -> jdbc.queryForObject(AnomalySql.UPSERT, Boolean.class,
                orgId, projectId, ref.serviceId(), ref.metric(), ref.scope(), f.anomalyType(), f.severity(),
                f.observed(), f.expected(), Math.max(0, f.score()), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(f.windowStart()), Timestamp.from(f.windowEnd()), evidenceJson, f.reason()));
    }

    /**
     * Groups skip reasons: text before a colon, numbers replaced by N, e.g.
     * "insufficient history: 12 usable baseline points, 30 needed" -> "insufficient history".
     */
    static String skipCategory(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unspecified";
        }
        int colon = reason.indexOf(':');
        String category = (colon > 0 ? reason.substring(0, colon) : reason).replaceAll("\\d+", "N");
        return category.length() <= 80 ? category : category.substring(0, 80);
    }

    static String truncate(String scope) {
        return scope.length() <= 300 ? scope : scope.substring(0, 300);
    }
}
