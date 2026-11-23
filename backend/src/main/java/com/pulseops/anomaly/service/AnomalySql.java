package com.pulseops.anomaly.service;

/** SQL of anomaly detection and the anomaly read model; parameter order documented per query. */
final class AnomalySql {

    private AnomalySql() {
    }

    /** Services with recent telemetry, across tenants; needs app.discovery = 'on'. since. */
    static final String DISCOVER = """
            SELECT c.organization_id, c.service_id
            FROM integration_connections c
            WHERE c.status = 'connected' AND c.last_telemetry_at >= ?
            ORDER BY c.organization_id, c.service_id
            """;

    /** org, service. */
    static final String SERVICE = """
            SELECT s.project_id, s.name FROM services s
            WHERE s.organization_id = ? AND s.id = ? AND s.deleted_at IS NULL AND s.status = 'active'
            """;

    /** Per minute, whole service. org, service, from, to. */
    static final String API_SERVICE_MINUTES = """
            SELECT m.bucket_start AS t, sum(m.request_count) AS requests, sum(m.error_count) AS errors,
                   max(m.duration_max_ms) AS max_ms, pulseops_histogram_sum(m.duration_buckets) AS buckets
            FROM api_metrics m
            WHERE m.organization_id = ? AND m.service_id = ? AND m.bucket_start >= ? AND m.bucket_start < ?
            GROUP BY m.bucket_start
            """;

    /** Per minute for the busiest endpoints. org, service, from, to, limit, org, service, from, to. */
    static final String API_ENDPOINT_MINUTES = """
            WITH top AS (
                SELECT m.http_method, m.route_template
                FROM api_metrics m
                WHERE m.organization_id = ? AND m.service_id = ? AND m.bucket_start >= ? AND m.bucket_start < ?
                GROUP BY m.http_method, m.route_template
                ORDER BY sum(m.request_count) DESC
                LIMIT ?
            )
            SELECT m.bucket_start AS t, m.http_method, m.route_template, sum(m.request_count) AS requests,
                   sum(m.error_count) AS errors, max(m.duration_max_ms) AS max_ms,
                   pulseops_histogram_sum(m.duration_buckets) AS buckets
            FROM api_metrics m
            JOIN top ON top.http_method = m.http_method AND top.route_template = m.route_template
            WHERE m.organization_id = ? AND m.service_id = ? AND m.bucket_start >= ? AND m.bucket_start < ?
            GROUP BY m.bucket_start, m.http_method, m.route_template
            """;

    /** Database p95 per minute. org, service, from, to. */
    static final String DB_MINUTES = """
            SELECT date_trunc('minute', d.occurred_at) AS t, count(*) AS calls,
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY d.duration_ms) AS p95
            FROM database_operations d
            WHERE d.organization_id = ? AND d.service_id = ? AND d.occurred_at >= ? AND d.occurred_at < ?
            GROUP BY 1
            """;

    /** Connection-pool used/max per minute, both metric naming conventions. org, service, from, to. */
    static final String POOL_MINUTES = """
            WITH per_series AS (
                SELECT m.bucket_start,
                       CASE WHEN m.name IN ('db.client.connection.max', 'db.client.connections.max') THEN 'max'
                            ELSE coalesce(m.attributes ->> 'db.client.connection.state', m.attributes ->> 'state')
                       END AS k,
                       m.value_sum / NULLIF(m.sample_count, 0) AS v
                FROM metric_points m
                WHERE m.organization_id = ? AND m.service_id = ? AND m.bucket_start >= ? AND m.bucket_start < ?
                  AND m.name IN ('db.client.connection.count', 'db.client.connections.usage',
                                 'db.client.connection.max', 'db.client.connections.max')
            )
            SELECT bucket_start AS t, sum(v) FILTER (WHERE k = 'used') AS used, sum(v) FILTER (WHERE k = 'max') AS max
            FROM per_series
            GROUP BY bucket_start
            """;

    /**
     * Insert, or merge into the active anomaly of the same (service, metric, scope): keep the
     * worse severity and higher score, extend the window, count occurrences.
     * org, project, service, metric, scope, type, severity, observed, expected, score, detected,
     * lastSeen, windowStart, windowEnd, evidence, reason. Returns whether a row was inserted.
     */
    static final String UPSERT = """
            INSERT INTO anomalies (organization_id, project_id, service_id, metric, scope, anomaly_type, severity,
                                   observed_value, expected_value, score, detected_at, last_seen_at, window_start,
                                   window_end, evidence, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (service_id, metric, scope) WHERE status IN ('open', 'linked') DO UPDATE SET
                anomaly_type   = EXCLUDED.anomaly_type,
                severity       = CASE WHEN array_position(ARRAY['low','medium','high','critical'], EXCLUDED.severity)
                                         > array_position(ARRAY['low','medium','high','critical'], anomalies.severity)
                                      THEN EXCLUDED.severity ELSE anomalies.severity END,
                observed_value = EXCLUDED.observed_value,
                expected_value = EXCLUDED.expected_value,
                score          = GREATEST(anomalies.score, EXCLUDED.score),
                last_seen_at   = EXCLUDED.last_seen_at,
                window_end     = GREATEST(anomalies.window_end, EXCLUDED.window_end),
                occurrences    = anomalies.occurrences + 1,
                evidence       = EXCLUDED.evidence,
                reason         = EXCLUDED.reason
            RETURNING (xmax = 0) AS inserted
            """;

    /** Active anomalies of a service not seen since the cutoff become resolved. org, service, cutoff. */
    static final String RESOLVE_STALE = """
            UPDATE anomalies SET status = 'resolved', resolved_at = now()
            WHERE organization_id = ? AND service_id = ? AND status IN ('open', 'linked') AND last_seen_at < ?
            """;

    static final String VIEW_COLUMNS = """
            a.id, a.service_id, s.name AS service_name, a.project_id, a.metric, a.scope, a.anomaly_type,
            a.severity, a.observed_value, a.expected_value, a.score, a.reason, a.detected_at, a.last_seen_at,
            a.window_start, a.window_end, a.occurrences, a.status, a.resolved_at, a.evidence::text AS evidence
            """;

    /**
     * Newest first (by detection time), keyset-paginated. org, statuses (comma list), service ×2,
     * minSeverityRank, from, to, cursorTime ×2, cursorId, limit.
     */
    static final String LIST = "SELECT " + VIEW_COLUMNS + """
            FROM anomalies a
            JOIN services s ON s.id = a.service_id
            WHERE a.organization_id = ?
              AND a.status = ANY (string_to_array(?, ','))
              AND (?::uuid IS NULL OR a.service_id = ?)
              AND array_position(ARRAY['low','medium','high','critical'], a.severity) >= ?
              AND a.last_seen_at >= ? AND a.detected_at < ?
              AND (?::timestamptz IS NULL OR (a.detected_at, a.id) < (?, ?::uuid))
            ORDER BY a.detected_at DESC, a.id DESC
            LIMIT ?
            """;

    /** org, id. */
    static final String GET = "SELECT " + VIEW_COLUMNS + """
            FROM anomalies a
            JOIN services s ON s.id = a.service_id
            WHERE a.organization_id = ? AND a.id = ?
            """;

    /** org, id. Only active anomalies can be dismissed. */
    static final String DISMISS = """
            UPDATE anomalies SET status = 'dismissed', resolved_at = now()
            WHERE organization_id = ? AND id = ? AND status IN ('open', 'linked')
            """;
}
