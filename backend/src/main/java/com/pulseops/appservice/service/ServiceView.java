package com.pulseops.appservice.service;

import java.time.Instant;
import java.util.UUID;

import com.pulseops.appservice.domain.ServiceStatus;
import com.pulseops.appservice.domain.Technology;

/**
 * A service with its integration connection state. {@code connectionStatus} is NOT_CONNECTED
 * until an integration is configured, and CONNECTED only after telemetry has actually arrived.
 */
public record ServiceView(UUID id, UUID projectId, String projectName, String name, String description,
                          Technology technology, ServiceStatus status, Connection connection, Instant createdAt) {

    public record Connection(String integrationType, String status, Instant firstTelemetryAt,
                             Instant lastTelemetryAt, String lastError) {
    }
}
