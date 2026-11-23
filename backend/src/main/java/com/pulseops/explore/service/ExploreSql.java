package com.pulseops.explore.service;

/** SQL of the logs and traces explorer; parameter order documented per query. */
final class ExploreSql {

    private ExploreSql() {
    }

    /** Shared log filters on alias {@code l}. */
    private static final String LOG_FILTERS = """
            l.organization_id = ? AND l.occurred_at >= ? AND l.occurred_at < ?
              AND (?::uuid IS NULL OR l.service_id = ?)
              AND l.level = ANY (string_to_array(?, ','))
              AND (?::text IS NULL OR l.message ILIKE ? ESCAPE '\\')
              AND (?::text IS NULL OR l.trace_id = ?)
            """;

    /**
     * Newest first, keyset-paginated. org, from, to, service ×2, levels (comma list),
     * pattern ×2, traceId ×2, cursorTime ×2, cursorId, limit.
     */
    static final String LOGS = """
            SELECT l.id, l.service_id, s.name AS service_name, l.occurred_at, l.level, l.message, l.logger,
                   l.trace_id, l.span_id, l.attributes::text AS attributes
            FROM application_logs l
            JOIN services s ON s.id = l.service_id
            WHERE""" + " " + LOG_FILTERS + """
              AND (?::timestamptz IS NULL OR (l.occurred_at, l.id) < (?, ?::uuid))
            ORDER BY l.occurred_at DESC, l.id DESC
            LIMIT ?
            """;

    /** step, step, org, from, to, service ×2, levels, pattern ×2, traceId ×2. */
    static final String LOG_HISTOGRAM = """
            SELECT to_timestamp(floor(extract(epoch FROM l.occurred_at) / ?) * ?) AS t, l.level, count(*) AS n
            FROM application_logs l
            WHERE""" + " " + LOG_FILTERS + """
            GROUP BY 1, 2
            """;

    /**
     * Trace summaries: traces with at least one span in the window (and service), aggregated
     * over all their spans. org, from, to, service ×2, org, minDuration ×2, errorsOnly,
     * pattern ×3, cursorTime ×2, cursorId, sortSlowest, limit.
     */
    static final String TRACES = """
            WITH matched AS (
                SELECT DISTINCT t.trace_id
                FROM distributed_traces t
                WHERE t.organization_id = ? AND t.start_time >= ? AND t.start_time < ?
                  AND (?::uuid IS NULL OR t.service_id = ?)
            ), agg AS (
                SELECT t.trace_id,
                       min(t.start_time) AS start_time,
                       max(t.start_time + t.duration_ms * interval '1 millisecond') AS end_time,
                       count(*) AS spans,
                       count(*) FILTER (WHERE t.status_code = 'ERROR') AS errors,
                       count(DISTINCT t.service_id) AS services,
                       (array_agg(t.name ORDER BY (t.parent_span_id IS NULL) DESC, t.start_time))[1] AS root_name,
                       (array_agg(t.service_id ORDER BY (t.parent_span_id IS NULL) DESC, t.start_time))[1]
                           AS root_service_id,
                       (array_agg(t.name ORDER BY t.start_time) FILTER (WHERE t.kind = 'SERVER'))[1] AS entry_name,
                       bool_or(t.parent_span_id IS NULL) AS has_root
                FROM distributed_traces t
                JOIN matched m ON m.trace_id = t.trace_id
                WHERE t.organization_id = ?
                GROUP BY t.trace_id
            )
            SELECT a.trace_id, a.start_time, a.spans, a.errors, a.services, a.root_name,
                   coalesce(a.entry_name, a.root_name) AS entry_name, a.root_service_id,
                   s.name AS root_service_name, a.has_root,
                   extract(epoch FROM (a.end_time - a.start_time)) * 1000 AS duration_ms
            FROM agg a
            LEFT JOIN services s ON s.id = a.root_service_id
            WHERE (?::double precision IS NULL OR extract(epoch FROM (a.end_time - a.start_time)) * 1000 >= ?)
              AND (NOT ? OR a.errors > 0)
              AND (?::text IS NULL OR a.root_name ILIKE ? ESCAPE '\\' OR a.entry_name ILIKE ? ESCAPE '\\')
              AND (?::timestamptz IS NULL OR (a.start_time, a.trace_id) < (?, ?))
            ORDER BY CASE WHEN ? THEN a.end_time - a.start_time END DESC NULLS LAST,
                     a.start_time DESC, a.trace_id DESC
            LIMIT ?
            """;

    /** org, traceId, limit. */
    static final String TRACE_SPANS = """
            SELECT t.span_id, t.parent_span_id, t.service_id, s.name AS service_name, t.name, t.kind,
                   t.start_time, t.duration_ms, t.status_code, t.status_message, t.attributes::text AS attributes
            FROM distributed_traces t
            JOIN services s ON s.id = t.service_id
            WHERE t.organization_id = ? AND t.trace_id = ?
            ORDER BY t.start_time
            LIMIT ?
            """;

    /** org, traceId, limit. */
    static final String TRACE_LOGS = """
            SELECT l.id, l.service_id, s.name AS service_name, l.occurred_at, l.level, l.message, l.logger,
                   l.trace_id, l.span_id, l.attributes::text AS attributes
            FROM application_logs l
            JOIN services s ON s.id = l.service_id
            WHERE l.organization_id = ? AND l.trace_id = ?
            ORDER BY l.occurred_at, l.id
            LIMIT ?
            """;
}
