package com.pulseops.incident.service;

/** SQL of incident correlation and management; parameter order documented per query. */
final class IncidentSql {

    private IncidentSql() {
    }

    // ------------------------------------------------------------------------- correlation

    /** Open anomalies not yet in an incident, oldest first. org. */
    static final String UNLINKED_ANOMALIES = """
            SELECT a.id, a.project_id, a.service_id, s.name AS service_name, a.metric, a.scope, a.anomaly_type,
                   a.severity, a.reason
            FROM anomalies a
            JOIN services s ON s.id = a.service_id
            WHERE a.organization_id = ? AND a.status = 'open'
              AND NOT EXISTS (SELECT 1 FROM incident_anomalies ia WHERE ia.anomaly_id = a.id)
            ORDER BY a.detected_at, a.id
            """;

    /** The active automatic incident for a dedup key, locked. org, dedupKey. */
    static final String ACTIVE_BY_DEDUP = """
            SELECT id, severity, status FROM incidents
            WHERE organization_id = ? AND dedup_key = ? AND status NOT IN ('resolved', 'closed')
            FOR UPDATE
            """;

    /**
     * Race-safe automatic creation (the partial unique index allows one active incident per
     * dedup key). org, project, service, title, summary, severity, dedupKey. Returns id, or
     * nothing when another transaction created it first.
     */
    static final String CREATE_AUTOMATIC = """
            INSERT INTO incidents (organization_id, project_id, service_id, title, summary, severity, status, source,
                                   dedup_key)
            VALUES (?, ?, ?, ?, ?, ?, 'open', 'automatic', ?)
            ON CONFLICT (organization_id, dedup_key) WHERE dedup_key IS NOT NULL AND status NOT IN ('resolved', 'closed')
            DO NOTHING
            RETURNING id
            """;

    /** org, incident, anomaly. */
    static final String LINK = """
            INSERT INTO incident_anomalies (organization_id, incident_id, anomaly_id) VALUES (?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    /** org, anomaly. */
    static final String MARK_LINKED = "UPDATE anomalies SET status = 'linked' WHERE organization_id = ? AND id = ? "
            + "AND status = 'open'";

    /** org, incident, type, actor, old, new, details json. */
    static final String EVENT = """
            INSERT INTO incident_events (organization_id, incident_id, event_type, actor_id, old_value, new_value, details,
                                         created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, clock_timestamp())
            """;

    /** Facts of every anomaly linked to an incident. incident. */
    static final String LINKED_FACTS = """
            SELECT s.name AS service_name, a.metric, a.scope, a.anomaly_type, a.severity, a.reason, a.service_id
            FROM incident_anomalies ia
            JOIN anomalies a ON a.id = ia.anomaly_id
            JOIN services s ON s.id = a.service_id
            WHERE ia.incident_id = ?
            ORDER BY a.detected_at, a.id
            """;

    /**
     * Keep the generated title, summary and scope of an automatic incident current while nobody
     * has acted on it (no timeline event with a human actor). title, summary, service, id, org.
     */
    static final String REFRESH_AUTOMATIC = """
            UPDATE incidents SET title = ?, summary = ?, service_id = ?
            WHERE id = ? AND organization_id = ? AND source = 'automatic' AND status = 'open'
              AND NOT EXISTS (SELECT 1 FROM incident_events e WHERE e.incident_id = incidents.id AND e.actor_id IS NOT NULL)
            """;

    /** org, id. */
    static final String SEVERITY_OF = "SELECT severity FROM incidents WHERE organization_id = ? AND id = ?";

    /** severity, id, org. */
    static final String SET_SEVERITY = "UPDATE incidents SET severity = ? WHERE id = ? AND organization_id = ?";

    /**
     * Automatic incidents whose linked anomalies have all ended and that have not been told
     * so since the last link. org.
     */
    static final String RECOVERED = """
            SELECT i.id, i.status
            FROM incidents i
            WHERE i.organization_id = ? AND i.source = 'automatic'
              AND i.status IN ('open', 'investigating', 'acknowledged')
              AND EXISTS (SELECT 1 FROM incident_anomalies ia WHERE ia.incident_id = i.id)
              AND NOT EXISTS (SELECT 1 FROM incident_anomalies ia JOIN anomalies a ON a.id = ia.anomaly_id
                              WHERE ia.incident_id = i.id AND a.status IN ('open', 'linked'))
              AND (SELECT e.event_type FROM incident_events e
                   WHERE e.incident_id = i.id AND e.event_type IN ('anomaly_linked', 'anomalies_recovered')
                   ORDER BY e.created_at DESC, e.event_type DESC LIMIT 1) = 'anomaly_linked'
            FOR UPDATE OF i
            """;

    /** notes, id, org. */
    static final String AUTO_RESOLVE = """
            UPDATE incidents SET status = 'resolved', resolved_at = now(),
                                 resolution_notes = coalesce(resolution_notes, ?)
            WHERE id = ? AND organization_id = ? AND status = 'open'
            """;

    // ---------------------------------------------------------------------------- read model

    static final String VIEW_COLUMNS = """
            i.id, i.project_id, p.name AS project_name, i.service_id, s.name AS service_name, i.title, i.summary,
            i.severity, i.status, i.source, i.detected_at, i.acknowledged_at, i.resolved_at, i.closed_at,
            i.assigned_to, u.full_name AS assignee_name, i.created_by, i.resolution_notes, i.updated_at,
            (SELECT count(*) FROM incident_anomalies ia WHERE ia.incident_id = i.id) AS anomaly_count
            """;

    static final String VIEW_FROM = """
            FROM incidents i
            JOIN projects p ON p.id = i.project_id
            LEFT JOIN services s ON s.id = i.service_id
            LEFT JOIN users u ON u.id = i.assigned_to
            """;

    /**
     * Incidents active at some point in the window, newest first, keyset-paginated. org,
     * statuses, minRank, project ×2, service ×2, from, to, cursorTime ×2, cursorId, limit.
     */
    static final String LIST = "SELECT " + VIEW_COLUMNS + VIEW_FROM + """
            WHERE i.organization_id = ?
              AND i.status = ANY (string_to_array(?, ','))
              AND array_position(ARRAY['low','medium','high','critical'], i.severity) >= ?
              AND (?::uuid IS NULL OR i.project_id = ?)
              AND (?::uuid IS NULL OR i.service_id = ?
                   OR EXISTS (SELECT 1 FROM incident_anomalies ia JOIN anomalies a ON a.id = ia.anomaly_id
                              WHERE ia.incident_id = i.id AND a.service_id = ?))
              AND coalesce(i.closed_at, i.resolved_at, 'infinity'::timestamptz) >= ? AND i.detected_at < ?
              AND (?::timestamptz IS NULL OR (i.detected_at, i.id) < (?, ?::uuid))
            ORDER BY i.detected_at DESC, i.id DESC
            LIMIT ?
            """;

    /** org, id. */
    static final String GET = "SELECT " + VIEW_COLUMNS + VIEW_FROM + "WHERE i.organization_id = ? AND i.id = ?";

    /** org, id — for updates. */
    static final String LOCK = "SELECT status, severity, assigned_to, title, summary, resolution_notes, source "
            + "FROM incidents WHERE organization_id = ? AND id = ? FOR UPDATE";

    /** org, incident. */
    static final String ANOMALIES = """
            SELECT a.id, a.service_id, s.name AS service_name, a.metric, a.scope, a.anomaly_type, a.severity,
                   a.observed_value, a.expected_value, a.score, a.reason, a.detected_at, a.last_seen_at,
                   a.window_start, a.window_end, a.status, a.occurrences
            FROM incident_anomalies ia
            JOIN anomalies a ON a.id = ia.anomaly_id
            JOIN services s ON s.id = a.service_id
            WHERE ia.organization_id = ? AND ia.incident_id = ?
            ORDER BY a.window_start, a.detected_at, a.id
            """;

    /** org, incident. */
    static final String EVENTS = """
            SELECT e.id, e.event_type, e.actor_id, u.full_name AS actor_name, e.old_value, e.new_value,
                   e.details::text AS details, e.created_at
            FROM incident_events e
            LEFT JOIN users u ON u.id = e.actor_id
            WHERE e.organization_id = ? AND e.incident_id = ?
            ORDER BY e.created_at, e.id
            """;

    /** org, incident. */
    static final String COMMENTS = """
            SELECT c.id, c.author_id, u.full_name AS author_name, c.body, c.created_at, c.updated_at
            FROM incident_comments c
            JOIN users u ON u.id = c.author_id
            WHERE c.organization_id = ? AND c.incident_id = ? AND c.deleted_at IS NULL
            ORDER BY c.created_at, c.id
            """;

    /** org, project, service, title, summary, severity, createdBy. */
    static final String CREATE_MANUAL = """
            INSERT INTO incidents (organization_id, project_id, service_id, title, summary, severity, status, source,
                                   created_by)
            VALUES (?, ?, ?, ?, ?, ?, 'open', 'manual', ?)
            RETURNING id
            """;

    /** org, incident, author, body. */
    static final String ADD_COMMENT = """
            INSERT INTO incident_comments (organization_id, incident_id, author_id, body, created_at, updated_at)
            VALUES (?, ?, ?, ?, clock_timestamp(), clock_timestamp())
            RETURNING id
            """;

    /** org, incident, comment, author. Only the author can delete their comment. */
    static final String DELETE_COMMENT = """
            UPDATE incident_comments SET deleted_at = now()
            WHERE organization_id = ? AND incident_id = ? AND id = ? AND author_id = ? AND deleted_at IS NULL
            """;

    /** org, user. */
    static final String ACTIVE_MEMBER = """
            SELECT count(*) FROM organization_members WHERE organization_id = ? AND user_id = ? AND status = 'active'
            """;

    /** org, project. */
    static final String PROJECT_ACTIVE = """
            SELECT count(*) FROM projects WHERE organization_id = ? AND id = ? AND deleted_at IS NULL AND status = 'active'
            """;

    /** org, service, project. */
    static final String SERVICE_IN_PROJECT = """
            SELECT count(*) FROM services
            WHERE organization_id = ? AND id = ? AND project_id = ? AND deleted_at IS NULL
            """;

    /**
     * Apply an edit, maintaining lifecycle timestamps. status, severity, assignee, title, summary,
     * notes, status (ack), status (resolved), status (reopen), status (closed), id, org.
     */
    static final String UPDATE = """
            UPDATE incidents SET status = ?, severity = ?, assigned_to = ?, title = ?, summary = ?, resolution_notes = ?,
                   acknowledged_at = CASE WHEN ? = 'acknowledged' AND acknowledged_at IS NULL THEN now()
                                          ELSE acknowledged_at END,
                   resolved_at = CASE WHEN ? = 'resolved' AND status <> 'resolved' THEN now()
                                      WHEN ? = 'open' THEN NULL ELSE resolved_at END,
                   closed_at = CASE WHEN ? = 'closed' THEN now() ELSE closed_at END
            WHERE id = ? AND organization_id = ?
            """;
}
