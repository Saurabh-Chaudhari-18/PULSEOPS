-- PulseOps V13: incident correlation support (ADR-025).

-- Timeline events added by correlation and lifecycle: anomalies recovering on their own,
-- edits to title/summary, deleted comments.
ALTER TABLE incident_events DROP CONSTRAINT incident_events_event_type_check;
ALTER TABLE incident_events ADD CONSTRAINT incident_events_event_type_check
    CHECK (event_type IN ('created', 'status_changed', 'severity_changed', 'assigned', 'comment_added',
                          'comment_deleted', 'anomaly_linked', 'anomalies_recovered', 'recommendation_added',
                          'resolved_notes_set', 'updated'));

-- Reverse lookups: anomaly -> incident, and incidents per project for correlation.
CREATE INDEX incident_anomalies_anomaly_idx ON incident_anomalies (anomaly_id);
CREATE INDEX incidents_project_status_idx ON incidents (project_id, status);

-- Anomalies per project for correlation.
CREATE INDEX anomalies_project_status_idx ON anomalies (project_id, status, detected_at DESC);
