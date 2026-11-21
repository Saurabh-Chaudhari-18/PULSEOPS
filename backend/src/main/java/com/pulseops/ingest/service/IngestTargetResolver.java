package com.pulseops.ingest.service;

import java.util.UUID;

import com.pulseops.appservice.domain.MonitoredService;
import com.pulseops.appservice.repository.MonitoredServiceRepository;
import com.pulseops.common.error.ApiException;
import com.pulseops.credential.service.CredentialAuthenticator.AuthenticatedKey;
import com.pulseops.integration.domain.ConnectionStatus;
import com.pulseops.integration.repository.IntegrationConnectionRepository;
import com.pulseops.project.domain.Project;
import com.pulseops.project.repository.ProjectRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decides which service telemetry belongs to, for every protocol. Must be called with the
 * key's organization bound as tenant context.
 *
 * <ul>
 *   <li>Service-scoped keys: only their own service. A requested name that differs is
 *       rejected when {@code strictName} is set (REST); OTLP attributes data by key.</li>
 *   <li>Project-wide keys: an existing service of the key's project, by name.</li>
 *   <li>Services are never auto-created; archived projects/services and disabled ingestion
 *       are refused.</li>
 * </ul>
 */
@Component
public class IngestTargetResolver {

    private final ProjectRepository projects;
    private final MonitoredServiceRepository services;
    private final IntegrationConnectionRepository connections;
    private final TransactionTemplate tx;

    public IngestTargetResolver(ProjectRepository projects, MonitoredServiceRepository services,
                                IntegrationConnectionRepository connections, TransactionTemplate tx) {
        this.projects = projects;
        this.services = services;
        this.connections = connections;
        this.tx = tx;
    }

    public UUID resolve(AuthenticatedKey key, String requestedName, boolean strictName) {
        return tx.execute(status -> {
            Project project = projects.findLive(key.organizationId(), key.projectId())
                    .orElseThrow(() -> forbidden("PROJECT_UNAVAILABLE", "The key's project no longer exists"));
            if (!project.isActive()) {
                throw forbidden("PROJECT_ARCHIVED", "The key's project is archived");
            }
            MonitoredService service;
            if (key.serviceId() != null) {
                service = services.findLive(key.organizationId(), key.serviceId())
                        .orElseThrow(() -> forbidden("SERVICE_UNAVAILABLE", "The key's service no longer exists"));
                if (strictName && requestedName != null && !requestedName.isBlank()
                        && !service.getName().equalsIgnoreCase(requestedName.trim())) {
                    throw forbidden("SERVICE_SCOPE_MISMATCH",
                            "This key is scoped to service '" + service.getName() + "'");
                }
            } else {
                if (requestedName == null || requestedName.isBlank()) {
                    throw ApiException.badRequest("SERVICE_REQUIRED",
                            "Project-wide keys must name the target service (REST: 'service' field; "
                                    + "OpenTelemetry: the service.name resource attribute)");
                }
                service = services.findLiveByName(key.organizationId(), key.projectId(), requestedName.trim())
                        .orElseThrow(() -> ApiException.notFound("UNKNOWN_SERVICE",
                                "No service named '" + requestedName.trim() + "' in this project; create it first"));
            }
            if (!service.isActive()) {
                throw forbidden("SERVICE_ARCHIVED", "Service '" + service.getName() + "' is archived");
            }
            boolean disabled = connections.findForService(key.organizationId(), service.getId())
                    .map(c -> c.getStatus() == ConnectionStatus.DISABLED)
                    .orElse(false);
            if (disabled) {
                throw forbidden("INGESTION_DISABLED", "Ingestion is disabled for service '" + service.getName() + "'");
            }
            return service.getId();
        });
    }

    private static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }
}
