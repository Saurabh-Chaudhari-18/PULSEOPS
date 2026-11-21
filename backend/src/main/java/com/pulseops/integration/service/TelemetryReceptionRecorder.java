package com.pulseops.integration.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.pulseops.integration.domain.IntegrationType;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Records real telemetry reception — the only code path that sets a service to CONNECTED.
 *
 * <p>Single upsert per call; the WHERE clause skips the write when the row was already
 * marked connected within the last 30 seconds, so steady traffic does not turn into a
 * write per batch. A DISABLED connection is never re-enabled by traffic. Must run with the
 * service's organization bound as tenant context.</p>
 */
@Component
public class TelemetryReceptionRecorder {

    static final String RECORD_SQL = """
            INSERT INTO integration_connections (organization_id, service_id, integration_type, status,
                                                 first_telemetry_at, last_telemetry_at)
            VALUES (?, ?, ?, 'connected', ?, ?)
            ON CONFLICT (service_id) DO UPDATE SET
                status = CASE WHEN integration_connections.status = 'disabled' THEN 'disabled' ELSE 'connected' END,
                first_telemetry_at = COALESCE(integration_connections.first_telemetry_at, EXCLUDED.first_telemetry_at),
                last_telemetry_at = EXCLUDED.last_telemetry_at,
                last_error = NULL
            WHERE integration_connections.status <> 'connected'
               OR integration_connections.last_telemetry_at IS NULL
               OR integration_connections.last_telemetry_at < EXCLUDED.last_telemetry_at - interval '30 seconds'
            """;

    static final String ERROR_SQL = """
            INSERT INTO integration_connections (organization_id, service_id, integration_type, status, last_error)
            VALUES (?, ?, ?, 'connection_error', ?)
            ON CONFLICT (service_id) DO UPDATE SET
                last_error = EXCLUDED.last_error,
                status = CASE
                    WHEN integration_connections.status IN ('disabled', 'connected') THEN integration_connections.status
                    ELSE 'connection_error' END
            """;

    private final JdbcTemplate jdbc;

    public TelemetryReceptionRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordReceived(UUID organizationId, UUID serviceId, IntegrationType type, Instant at) {
        Timestamp ts = Timestamp.from(at);
        jdbc.update(RECORD_SQL, organizationId, serviceId, dbValue(type), ts, ts);
    }

    /**
     * A correctly authenticated application sent data PulseOps had to reject (e.g. invalid
     * payload). A service that is already connected stays connected; one still waiting for
     * its first data moves to CONNECTION_ERROR so the onboarding wizard can show why.
     */
    public void recordRejected(UUID organizationId, UUID serviceId, IntegrationType type, String error) {
        String message = error == null ? "Telemetry rejected" : error.substring(0, Math.min(error.length(), 500));
        jdbc.update(ERROR_SQL, organizationId, serviceId, dbValue(type), message);
    }

    private static String dbValue(IntegrationType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }
}
