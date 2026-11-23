package com.pulseops.monitoring.service;

import java.util.List;

/**
 * SQL of the monitoring read model, kept apart so it can be checked against PostgreSQL
 * directly (see scripts/verify_migrations.sh). Parameter order is documented per query.
 */
final class MonitoringSql {

    private MonitoringSql() {
    }

    /** Aggregates read by {@link LatencyStats#read}. */
    static final String STATS_COLUMNS = """
            coalesce(sum(m.request_count), 0) AS requests,
            coalesce(sum(m.error_count), 0) AS server_errors,
            coalesce(sum(m.request_count) FILTER (WHERE m.status_class = '4xx'), 0) AS client_errors,
            coalesce(sum(m.duration_sum_ms), 0) AS duration_sum,
            max(m.duration_max_ms) AS duration_max,
            pulseops_histogram_sum(m.duration_buckets) AS buckets
            """;

    /** Window predicate on alias {@code m}: organization, from (inclusive), to (exclusive). */
    static final String RANGE = " m.organization_id = ? AND m.bucket_start >= ? AND m.bucket_start < ? ";

    /** org, from, to, service. */
    static final String SERVICE_STATS = "SELECT " + STATS_COLUMNS + " FROM api_metrics m WHERE" + RANGE
            + "AND m.service_id = ?";

    /** org, from, to, service. */
    static final String SERVICE_STATUS_CLASSES = "SELECT m.status_class, sum(m.request_count) AS n "
            + "FROM api_metrics m WHERE" + RANGE + "AND m.service_id = ? GROUP BY m.status_class";

    /** step, step, org, from, to, service, route, route, method, method (route/method null = all). */
    static final String SERIES = """
            SELECT to_timestamp(floor(extract(epoch FROM m.bucket_start) / ?) * ?) AS t,
            """ + STATS_COLUMNS + """
            FROM api_metrics m WHERE""" + RANGE + """
            AND m.service_id = ?
              AND (?::text IS NULL OR m.route_template = ?)
              AND (?::text IS NULL OR m.http_method = ?)
            GROUP BY 1
            """;

    /** org, from, to, service, pattern, pattern, limit. */
    static final String ENDPOINTS = """
            SELECT m.http_method, m.route_template, max(m.bucket_start) AS last_seen,
            """ + STATS_COLUMNS + """
            FROM api_metrics m WHERE""" + RANGE + """
            AND m.service_id = ?
              AND (?::text IS NULL OR m.route_template ILIKE ? ESCAPE '\\')
            GROUP BY m.http_method, m.route_template
            ORDER BY sum(m.request_count) DESC
            LIMIT ?
            """;

    /** org, from, to (metrics join), org, project, project (project null = all). */
    static final String OVERVIEW = """
            SELECT s.id, s.name, p.id AS project_id, p.name AS project_name,
               c.status AS connection_status, c.last_telemetry_at,
            """ + STATS_COLUMNS + """
            FROM services s
            JOIN projects p ON p.id = s.project_id AND p.deleted_at IS NULL
            LEFT JOIN integration_connections c ON c.service_id = s.id
            LEFT JOIN api_metrics m ON m.service_id = s.id AND""" + RANGE + """
            WHERE s.organization_id = ? AND s.deleted_at IS NULL AND s.status = 'active'
              AND p.status = 'active'
              AND (?::uuid IS NULL OR s.project_id = ?)
            GROUP BY s.id, s.name, p.id, p.name, c.status, c.last_telemetry_at
            """;

    /** org, from, to, project, project. */
    static final String ORGANIZATION_STATS = "SELECT " + STATS_COLUMNS + " FROM api_metrics m "
            + "JOIN services s ON s.id = m.service_id AND s.deleted_at IS NULL AND s.status = 'active' "
            + "JOIN projects p ON p.id = s.project_id AND p.deleted_at IS NULL AND p.status = 'active' "
            + "WHERE" + RANGE + "AND (?::uuid IS NULL OR s.project_id = ?)";

    /** JVM runtime metric names (OpenTelemetry semantic conventions, Java agent 2.x). */
    static final String CPU = "jvm.cpu.recent_utilization";
    static final String MEMORY_USED = "jvm.memory.used";
    static final String MEMORY_LIMIT = "jvm.memory.limit";
    static final String THREADS = "jvm.thread.count";
    static final String CLASSES = "jvm.class.count";

    /**
     * Per step: the average over minutes of (sum over series of the series' minute average),
     * so per-pool heap series add up to total heap and per-state thread series to all threads.
     * org, from, to, service, step, step.
     */
    static final String RUNTIME = """
            WITH per_minute AS (
                SELECT m.bucket_start, m.name, sum(m.value_sum / NULLIF(m.sample_count, 0)) AS v
                FROM metric_points m
                WHERE""" + RANGE + """
                AND m.service_id = ?
                  AND (m.name IN ('jvm.cpu.recent_utilization', 'jvm.thread.count', 'jvm.class.count')
                       OR (m.name IN ('jvm.memory.used', 'jvm.memory.limit')
                           AND m.attributes ->> 'jvm.memory.type' = 'heap'))
                GROUP BY m.bucket_start, m.name)
            SELECT to_timestamp(floor(extract(epoch FROM bucket_start) / ?) * ?) AS t, name, avg(v) AS v
            FROM per_minute
            GROUP BY 1, 2
            """;

    /** org, from, to, service. */
    static final String METRIC_NAMES = "SELECT DISTINCT m.name FROM metric_points m WHERE" + RANGE
            + "AND m.service_id = ? ORDER BY m.name LIMIT 200";

    // ------------------------------------------------------------------ database monitoring
    // database_operations holds one row per call, so percentiles are exact (percentile_cont).

    /** Window predicate on alias {@code d}: organization, from (inclusive), to (exclusive), service. */
    static final String DB_RANGE = " d.organization_id = ? AND d.occurred_at >= ? AND d.occurred_at < ? "
            + "AND d.service_id = ? ";

    /** Aggregates read by {@link DbStats#read}; takes one parameter: the slow threshold in ms. */
    static final String DB_STATS_COLUMNS = """
            count(*) AS calls,
            count(*) FILTER (WHERE d.is_error) AS errors,
            count(*) FILTER (WHERE d.duration_ms >= ?) AS slow_calls,
            coalesce(sum(d.duration_ms), 0) AS total_ms,
            avg(d.duration_ms) AS avg_ms,
            percentile_cont(0.5) WITHIN GROUP (ORDER BY d.duration_ms) AS p50_ms,
            percentile_cont(0.95) WITHIN GROUP (ORDER BY d.duration_ms) AS p95_ms,
            percentile_cont(0.99) WITHIN GROUP (ORDER BY d.duration_ms) AS p99_ms,
            max(d.duration_ms) AS max_ms
            """;

    /** slowMs, org, from, to, service. */
    static final String DB_SUMMARY = "SELECT " + DB_STATS_COLUMNS + " FROM database_operations d WHERE" + DB_RANGE;

    /** org, from, to, service. */
    static final String DB_BREAKDOWN = """
            SELECT d.db_system, d.operation_type, count(*) AS calls,
                   count(*) FILTER (WHERE d.is_error) AS errors, coalesce(sum(d.duration_ms), 0) AS total_ms
            FROM database_operations d
            WHERE""" + DB_RANGE + """
            GROUP BY d.db_system, d.operation_type
            ORDER BY total_ms DESC
            """;

    /** slowMs, org, from, to, service, pattern ×3, limit. Busiest (by total time) first. */
    static final String DB_OPERATIONS = """
            SELECT d.db_system, d.operation_type, d.operation, d.table_name, max(d.occurred_at) AS last_seen,
                   (array_agg(d.trace_id ORDER BY d.duration_ms DESC)
                        FILTER (WHERE d.trace_id IS NOT NULL))[1] AS slowest_trace_id,
            """ + DB_STATS_COLUMNS + """
            FROM database_operations d
            WHERE""" + DB_RANGE + """
              AND (?::text IS NULL OR d.operation ILIKE ? ESCAPE '\\' OR d.table_name ILIKE ? ESCAPE '\\')
            GROUP BY d.db_system, d.operation_type, d.operation, d.table_name
            ORDER BY sum(d.duration_ms) DESC
            LIMIT ?
            """;

    /** step, step, slowMs, org, from, to, service, operation, operation (null = all). */
    static final String DB_SERIES = """
            SELECT to_timestamp(floor(extract(epoch FROM d.occurred_at) / ?) * ?) AS t,
            """ + DB_STATS_COLUMNS + """
            FROM database_operations d
            WHERE""" + DB_RANGE + """
              AND (?::text IS NULL OR d.operation = ?)
            GROUP BY 1
            """;

    /** org, from, to, service, minDurationMs, errorsOnly, limit. Slowest first. */
    static final String DB_CALLS = """
            SELECT d.occurred_at, d.db_system, d.operation_type, d.operation, d.table_name, d.duration_ms,
                   d.is_error, d.error_message, d.trace_id, d.span_id
            FROM database_operations d
            WHERE""" + DB_RANGE + """
              AND d.duration_ms >= ?
              AND (NOT ? OR d.is_error)
            ORDER BY d.duration_ms DESC, d.occurred_at DESC
            LIMIT ?
            """;

    /** Connection-pool metric names: stable semantic conventions and the older experimental ones. */
    static final List<String> POOL_METRICS = List.of(
            "db.client.connection.count", "db.client.connections.usage",
            "db.client.connection.max", "db.client.connections.max",
            "db.client.connection.pending_requests", "db.client.connections.pending_requests");

    /**
     * Per step: average over minutes of the pool gauges summed across pools, keyed
     * used / idle / max / pending. org, from, to, service, step, step.
     */
    static final String DB_POOL = """
            WITH per_minute AS (
                SELECT m.bucket_start,
                       CASE
                           WHEN m.name IN ('db.client.connection.count', 'db.client.connections.usage')
                               THEN coalesce(m.attributes ->> 'db.client.connection.state', m.attributes ->> 'state')
                           WHEN m.name IN ('db.client.connection.max', 'db.client.connections.max') THEN 'max'
                           ELSE 'pending'
                       END AS k,
                       sum(m.value_sum / NULLIF(m.sample_count, 0)) AS v
                FROM metric_points m
                WHERE""" + RANGE + """
                AND m.service_id = ?
                  AND m.name IN ('db.client.connection.count', 'db.client.connections.usage',
                                 'db.client.connection.max', 'db.client.connections.max',
                                 'db.client.connection.pending_requests', 'db.client.connections.pending_requests')
                GROUP BY 1, 2)
            SELECT to_timestamp(floor(extract(epoch FROM bucket_start) / ?) * ?) AS t, k, avg(v) AS v, max(v) AS peak
            FROM per_minute
            WHERE k IN ('used', 'idle', 'max', 'pending')
            GROUP BY 1, 2
            """;

    /** org, from, to, service. */
    static final String DB_POOL_NAMES = """
            SELECT DISTINCT coalesce(m.attributes ->> 'db.client.connection.pool.name', m.attributes ->> 'pool.name')
                   AS pool
            FROM metric_points m
            WHERE""" + RANGE + """
            AND m.service_id = ?
              AND m.name IN ('db.client.connection.count', 'db.client.connections.usage')
            ORDER BY 1
            LIMIT 20
            """;

    /** Organization-wide database totals for the overview: slowMs, org, from, to, project, project. */
    static final String DB_ORGANIZATION_STATS = "SELECT " + DB_STATS_COLUMNS + """
             FROM database_operations d
            JOIN services s ON s.id = d.service_id AND s.deleted_at IS NULL AND s.status = 'active'
            JOIN projects p ON p.id = s.project_id AND p.deleted_at IS NULL AND p.status = 'active'
            WHERE d.organization_id = ? AND d.occurred_at >= ? AND d.occurred_at < ?
              AND (?::uuid IS NULL OR s.project_id = ?)
            """;
}
