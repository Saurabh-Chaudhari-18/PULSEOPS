package com.pulseops.integration.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.pulseops.appservice.domain.MonitoredService;
import com.pulseops.appservice.service.ServiceCatalogService;
import com.pulseops.audit.service.AuditService;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.credential.repository.IntegrationCredentialRepository;
import com.pulseops.integration.domain.ConnectionStatus;
import com.pulseops.integration.domain.IntegrationConnection;
import com.pulseops.integration.domain.IntegrationType;
import com.pulseops.integration.repository.IntegrationConnectionRepository;
import com.pulseops.project.service.ProjectEvents;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Onboarding wizard state per service: method selection, verification polling, enable/disable. */
@Service
public class IntegrationService {

    /** Data older than this no longer counts as "currently receiving". */
    public static final Duration FRESHNESS = Duration.ofMinutes(15);

    private final IntegrationConnectionRepository connections;
    private final IntegrationCredentialRepository credentials;
    private final ServiceCatalogService services;
    private final AuditService audit;
    private final Clock clock;

    public IntegrationService(IntegrationConnectionRepository connections, IntegrationCredentialRepository credentials,
                              ServiceCatalogService services, AuditService audit, Clock clock) {
        this.connections = connections;
        this.credentials = credentials;
        this.services = services;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ConnectionStatusView configure(OrgAccess access, UUID serviceId, IntegrationType type) {
        MonitoredService service = services.requireLive(access.organizationId(), serviceId);
        IntegrationConnection connection = connections.findForService(access.organizationId(), serviceId)
                .map(existing -> {
                    existing.configure(type);
                    return existing;
                })
                .orElseGet(() -> connections.save(new IntegrationConnection(access.organizationId(), serviceId, type)));
        audit.record(access.userId(), "integration.configured", "service", serviceId, Map.of("type", type.name()));
        return view(access, service, connection);
    }

    /** Polled by the wizard's "Verify connection" step. */
    @Transactional(readOnly = true)
    public ConnectionStatusView status(OrgAccess access, UUID serviceId) {
        MonitoredService service = services.requireLive(access.organizationId(), serviceId);
        return view(access, service, connections.findForService(access.organizationId(), serviceId).orElse(null));
    }

    @Transactional
    public ConnectionStatusView disable(OrgAccess access, UUID serviceId) {
        MonitoredService service = services.requireLive(access.organizationId(), serviceId);
        IntegrationConnection connection = connections.findForService(access.organizationId(), serviceId)
                .orElseGet(() -> connections.save(new IntegrationConnection(access.organizationId(), serviceId,
                        IntegrationType.REST_API)));
        connection.disable();
        audit.record(access.userId(), "integration.disabled", "service", serviceId, Map.of());
        return view(access, service, connection);
    }

    @Transactional
    public ConnectionStatusView enable(OrgAccess access, UUID serviceId) {
        MonitoredService service = services.requireLive(access.organizationId(), serviceId);
        IntegrationConnection connection = connections.findForService(access.organizationId(), serviceId).orElse(null);
        if (connection != null) {
            connection.enable(clock.instant(), FRESHNESS);
            audit.record(access.userId(), "integration.enabled", "service", serviceId, Map.of());
        }
        return view(access, service, connection);
    }

    /** Archiving or deleting a service stops ingestion for it. */
    @EventListener
    public void onServiceDeactivated(ProjectEvents.ServiceDeactivated event) {
        connections.findForService(event.organizationId(), event.serviceId())
                .ifPresent(IntegrationConnection::disable);
    }

    private ConnectionStatusView view(OrgAccess access, MonitoredService service, IntegrationConnection connection) {
        Instant now = clock.instant();
        long usableKeys = credentials.countUsableFor(access.organizationId(), service.getProjectId(),
                service.getId(), now);
        if (connection == null) {
            return new ConnectionStatusView(service.getId(), null, ConnectionStatus.NOT_CONNECTED, null, null, null,
                    false, usableKeys, nextStep(ConnectionStatus.NOT_CONNECTED, usableKeys, false));
        }
        boolean receiving = connection.getStatus() == ConnectionStatus.CONNECTED
                && connection.getLastTelemetryAt() != null
                && connection.getLastTelemetryAt().isAfter(now.minus(FRESHNESS));
        return new ConnectionStatusView(service.getId(), connection.getIntegrationType(), connection.getStatus(),
                connection.getFirstTelemetryAt(), connection.getLastTelemetryAt(), connection.getLastError(),
                receiving, usableKeys, nextStep(connection.getStatus(), usableKeys, receiving));
    }

    static String nextStep(ConnectionStatus status, long usableKeys, boolean receiving) {
        return switch (status) {
            case NOT_CONNECTED -> "Choose an integration method for this service.";
            case DISABLED -> "Ingestion is disabled for this service. Enable it to accept telemetry again.";
            case CONNECTION_ERROR -> "Telemetry was received but rejected. Check the last error and your configuration.";
            case WAITING_FOR_DATA -> usableKeys == 0
                    ? "Create an integration key, then configure your application with it."
                    : "Start your application with the configuration shown. This page updates when data arrives.";
            case CONNECTED -> receiving
                    ? "Telemetry is arriving. Open the dashboard."
                    : "No telemetry in the last " + FRESHNESS.toMinutes() + " minutes. Is the application running?";
        };
    }

    public record ConnectionStatusView(UUID serviceId, IntegrationType integrationType, ConnectionStatus status,
                                       Instant firstTelemetryAt, Instant lastTelemetryAt, String lastError,
                                       boolean receivingNow, long usableKeys, String nextStep) {
    }
}
