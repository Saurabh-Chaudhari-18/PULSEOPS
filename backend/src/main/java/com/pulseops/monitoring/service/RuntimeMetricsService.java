package com.pulseops.monitoring.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.pulseops.appservice.service.ServiceCatalogService;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.monitoring.service.MonitoringViews.RuntimeMetrics;
import com.pulseops.monitoring.service.MonitoringViews.RuntimePoint;
import com.pulseops.monitoring.service.MonitoringViews.Window;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * JVM runtime metrics (CPU, heap, threads, classes) from OTLP metric points, when the
 * service exports them — the OpenTelemetry Java agent does by default. Values the service
 * did not report stay null; nothing is estimated.
 */
@Service
public class RuntimeMetricsService {

    private final JdbcTemplate jdbc;
    private final ServiceCatalogService services;

    public RuntimeMetricsService(JdbcTemplate jdbc, ServiceCatalogService services) {
        this.jdbc = jdbc;
        this.services = services;
    }

    @Transactional(readOnly = true)
    public RuntimeMetrics runtime(OrgAccess access, UUID serviceId, TimeWindow window) {
        services.get(access, serviceId);
        Map<Instant, Map<String, Double>> byTime = new HashMap<>();
        jdbc.query(MonitoringSql.RUNTIME, rs -> {
                    double v = rs.getDouble("v");
                    if (!rs.wasNull()) {
                        byTime.computeIfAbsent(rs.getTimestamp("t").toInstant(), t -> new HashMap<>())
                                .put(rs.getString("name"), v);
                    }
                }, access.organizationId(), Timestamp.from(window.from()), Timestamp.from(window.to()), serviceId,
                window.stepSeconds(), window.stepSeconds());
        List<String> names = jdbc.queryForList(MonitoringSql.METRIC_NAMES, String.class, access.organizationId(),
                Timestamp.from(window.from()), Timestamp.from(window.to()), serviceId);
        return new RuntimeMetrics(serviceId, Window.of(window), !byTime.isEmpty(), names, points(window, byTime));
    }

    static List<RuntimePoint> points(TimeWindow window, Map<Instant, Map<String, Double>> byTime) {
        List<RuntimePoint> points = new ArrayList<>();
        for (Instant t : window.points()) {
            Map<String, Double> v = byTime.getOrDefault(t, Map.of());
            Double cpu = v.get(MonitoringSql.CPU);
            points.add(new RuntimePoint(t, cpu == null ? null : LatencyStats.round4(cpu),
                    round(v.get(MonitoringSql.MEMORY_USED)), round(v.get(MonitoringSql.MEMORY_LIMIT)),
                    round(v.get(MonitoringSql.THREADS)), round(v.get(MonitoringSql.CLASSES))));
        }
        return points;
    }

    private static Double round(Double value) {
        return value == null ? null : (double) Math.round(value);
    }
}
