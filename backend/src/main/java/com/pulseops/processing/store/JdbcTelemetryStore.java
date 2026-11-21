package com.pulseops.processing.store;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseops.processing.model.IngestBatch;
import com.pulseops.processing.model.TelemetryEvent;
import com.pulseops.processing.service.ApiMetricAggregator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL implementation of {@link TelemetryStore} using batched JDBC statements. */
@Repository
public class JdbcTelemetryStore implements TelemetryStore {

    static final String INSERT_EVENT = """
            INSERT INTO telemetry_events (organization_id, service_id, event_type, name, occurred_at, received_at,
                                          attributes)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
            """;

    static final String MERGE_METRIC = """
            INSERT INTO api_metrics (organization_id, service_id, route_template, http_method, status_class,
                                     bucket_start, bucket_seconds, request_count, error_count, duration_sum_ms,
                                     duration_min_ms, duration_max_ms, duration_buckets)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (service_id, route_template, http_method, status_class, bucket_start, bucket_seconds)
            DO UPDATE SET
                request_count   = api_metrics.request_count + EXCLUDED.request_count,
                error_count     = api_metrics.error_count + EXCLUDED.error_count,
                duration_sum_ms = api_metrics.duration_sum_ms + EXCLUDED.duration_sum_ms,
                duration_min_ms = LEAST(api_metrics.duration_min_ms, EXCLUDED.duration_min_ms),
                duration_max_ms = GREATEST(api_metrics.duration_max_ms, EXCLUDED.duration_max_ms),
                duration_buckets = pulseops_histogram_add(api_metrics.duration_buckets, EXCLUDED.duration_buckets)
            """;

    static final String INSERT_ERROR_LOG = """
            INSERT INTO application_logs (organization_id, service_id, occurred_at, level, message, logger,
                                          trace_id, span_id, attributes, received_at)
            VALUES (?, ?, ?, 'ERROR', ?, ?, ?, ?, ?::jsonb, ?)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcTelemetryStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveEvents(IngestBatch batch) {
        Timestamp received = Timestamp.from(batch.receivedAt());
        List<Object[]> rows = new ArrayList<>(batch.events().size());
        for (TelemetryEvent e : batch.events()) {
            rows.add(new Object[]{batch.organizationId(), batch.serviceId(), e.type().dbValue(), e.name(),
                    Timestamp.from(e.occurredAt()), received, toJson(eventAttributes(e))});
        }
        jdbc.batchUpdate(INSERT_EVENT, rows);
    }

    @Override
    public void mergeApiMetrics(IngestBatch batch, Collection<ApiMetricAggregator.Aggregate> aggregates) {
        if (aggregates.isEmpty()) {
            return;
        }
        List<ApiMetricAggregator.Aggregate> list = new ArrayList<>(aggregates);
        jdbc.batchUpdate(MERGE_METRIC, list, list.size(), (PreparedStatement ps, ApiMetricAggregator.Aggregate a) -> {
            ps.setObject(1, batch.organizationId());
            ps.setObject(2, batch.serviceId());
            ps.setString(3, a.key().route());
            ps.setString(4, a.key().method());
            ps.setString(5, a.key().statusClass());
            ps.setTimestamp(6, Timestamp.from(a.key().bucketStart()));
            ps.setInt(7, ApiMetricAggregator.BUCKET_SECONDS);
            ps.setLong(8, a.requests());
            ps.setLong(9, a.errors());
            ps.setDouble(10, a.sumMs());
            ps.setDouble(11, a.minMs());
            ps.setDouble(12, a.maxMs());
            ps.setArray(13, bigintArray(ps, a.histogram()));
        });
    }

    @Override
    public void saveErrorLogs(IngestBatch batch, List<TelemetryEvent> errorEvents) {
        if (errorEvents.isEmpty()) {
            return;
        }
        Timestamp received = Timestamp.from(batch.receivedAt());
        List<Object[]> rows = new ArrayList<>(errorEvents.size());
        for (TelemetryEvent e : errorEvents) {
            TelemetryEvent.Failure f = e.error();
            String message = f != null && f.message() != null && !f.message().isBlank()
                    ? e.name() + ": " + f.message() : e.name();
            Map<String, Object> attributes = new LinkedHashMap<>(e.attributes());
            attributes.put("pulseops.source", batch.source().name().toLowerCase(java.util.Locale.ROOT));
            if (f != null && f.stackTrace() != null) {
                attributes.put("exception.stacktrace", f.stackTrace());
            }
            rows.add(new Object[]{batch.organizationId(), batch.serviceId(), Timestamp.from(e.occurredAt()), message,
                    f == null ? null : f.type(), e.traceId(), e.spanId(), toJson(attributes), received});
        }
        jdbc.batchUpdate(INSERT_ERROR_LOG, rows);
    }

    /** User attributes plus the event's structured fields under OpenTelemetry-style names. */
    static Map<String, Object> eventAttributes(TelemetryEvent e) {
        Map<String, Object> out = new LinkedHashMap<>(e.attributes());
        if (e.http() != null) {
            out.put("http.request.method", e.http().method());
            out.put("http.route", e.http().route());
            if (e.http().statusCode() != null) {
                out.put("http.response.status_code", e.http().statusCode());
            }
            out.put("duration_ms", e.http().durationMs());
        }
        if (e.metric() != null) {
            out.put("metric.value", e.metric().value());
            if (e.metric().unit() != null) {
                out.put("metric.unit", e.metric().unit());
            }
        }
        if (e.error() != null) {
            if (e.error().type() != null) {
                out.put("exception.type", e.error().type());
            }
            if (e.error().message() != null) {
                out.put("exception.message", e.error().message());
            }
        }
        if (e.traceId() != null) {
            out.put("trace_id", e.traceId());
        }
        if (e.spanId() != null) {
            out.put("span_id", e.spanId());
        }
        return out;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Attributes are not JSON-serializable", e);
        }
    }

    private static Array bigintArray(PreparedStatement ps, long[] values) throws SQLException {
        Long[] boxed = new Long[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        return ps.getConnection().createArrayOf("bigint", boxed);
    }
}
