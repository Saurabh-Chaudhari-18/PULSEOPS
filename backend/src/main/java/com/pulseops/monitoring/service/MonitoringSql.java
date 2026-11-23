package com.pulseops.monitoring.service;

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
}
