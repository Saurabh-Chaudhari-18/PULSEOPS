package com.pulseops.appservice.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.pulseops.appservice.domain.MonitoredService;
import com.pulseops.appservice.domain.ServiceStatus;
import com.pulseops.appservice.domain.Technology;
import com.pulseops.appservice.repository.MonitoredServiceRepository;
import com.pulseops.audit.service.AuditService;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.persistence.LikePatterns;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.web.PageQuery;
import com.pulseops.common.web.PageResponse;
import com.pulseops.project.domain.Project;
import com.pulseops.project.service.ProjectEvents;
import com.pulseops.project.service.ProjectService;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Services inside projects: create, list, update, archive/restore, soft delete. */
@Service
public class ServiceCatalogService {

    private static final String VIEW_SELECT = """
            SELECT s.id, s.project_id, p.name AS project_name, s.name, s.description, s.technology, s.status,
                   s.created_at, c.integration_type, c.status AS connection_status, c.first_telemetry_at,
                   c.last_telemetry_at, c.last_error
            FROM services s
            JOIN projects p ON p.id = s.project_id AND p.deleted_at IS NULL
            LEFT JOIN integration_connections c ON c.service_id = s.id
            """;

    private final MonitoredServiceRepository services;
    private final ProjectService projectService;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ServiceCatalogService(MonitoredServiceRepository services, ProjectService projectService,
                                 AuditService audit, ApplicationEventPublisher events, JdbcTemplate jdbc,
                                 Clock clock) {
        this.services = services;
        this.projectService = projectService;
        this.audit = audit;
        this.events = events;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public ServiceView create(OrgAccess access, UUID projectId, String name, String description,
                              Technology technology) {
        Project project = projectService.requireLive(access.organizationId(), projectId);
        if (!project.isActive()) {
            throw ApiException.conflict("PROJECT_ARCHIVED", "Restore the project before adding services");
        }
        String trimmed = name.trim();
        if (services.nameTaken(projectId, trimmed)) {
            throw nameTaken();
        }
        MonitoredService service = services.save(new MonitoredService(access.organizationId(), projectId, trimmed,
                description == null || description.isBlank() ? null : description.trim(), technology));
        audit.record(access.userId(), "service.created", "service", service.getId(),
                Map.of("name", trimmed, "projectId", projectId.toString()));
        services.flush();
        return get(access, service.getId());
    }

    /** Services of the organization, optionally within one project, with connection state. */
    @Transactional(readOnly = true)
    public PageResponse<ServiceView> list(OrgAccess access, UUID projectId, String search, PageQuery page) {
        String pattern = LikePatterns.contains(search);
        String where = """
                WHERE s.organization_id = ? AND s.deleted_at IS NULL
                  AND (?::uuid IS NULL OR s.project_id = ?)
                  AND (?::text IS NULL OR s.name ILIKE ? ESCAPE '\\')
                """;
        long total = jdbc.queryForObject(
                "SELECT count(*) FROM services s JOIN projects p ON p.id = s.project_id AND p.deleted_at IS NULL "
                        + where, Long.class, access.organizationId(), projectId, projectId, pattern, pattern);
        List<ServiceView> rows = jdbc.query(
                VIEW_SELECT + where + " ORDER BY lower(p.name), lower(s.name) LIMIT ? OFFSET ?",
                ServiceCatalogService::mapView, access.organizationId(), projectId, projectId, pattern, pattern,
                page.size(), page.offset());
        return PageResponse.of(rows, page, total);
    }

    @Transactional(readOnly = true)
    public ServiceView get(OrgAccess access, UUID serviceId) {
        List<ServiceView> rows = jdbc.query(
                VIEW_SELECT + " WHERE s.organization_id = ? AND s.id = ? AND s.deleted_at IS NULL",
                ServiceCatalogService::mapView, access.organizationId(), serviceId);
        if (rows.isEmpty()) {
            throw notFound();
        }
        return rows.get(0);
    }

    @Transactional
    public ServiceView update(OrgAccess access, UUID serviceId, String name, String description,
                              Technology technology) {
        MonitoredService service = requireLive(access.organizationId(), serviceId);
        if (name != null && services.nameTakenByOther(service.getProjectId(), name.trim(), serviceId)) {
            throw nameTaken();
        }
        service.update(name, description, technology);
        audit.record(access.userId(), "service.updated", "service", serviceId, Map.of("name", service.getName()));
        services.flush();
        return get(access, serviceId);
    }

    @Transactional
    public ServiceView archive(OrgAccess access, UUID serviceId) {
        MonitoredService service = requireLive(access.organizationId(), serviceId);
        if (service.getStatus() != ServiceStatus.ARCHIVED) {
            service.archive();
            events.publishEvent(new ProjectEvents.ServiceDeactivated(access.organizationId(),
                    service.getProjectId(), serviceId, false, access.userId()));
            audit.record(access.userId(), "service.archived", "service", serviceId, Map.of());
        }
        services.flush();
        return get(access, serviceId);
    }

    @Transactional
    public ServiceView restore(OrgAccess access, UUID serviceId) {
        MonitoredService service = requireLive(access.organizationId(), serviceId);
        if (!projectService.requireLive(access.organizationId(), service.getProjectId()).isActive()) {
            throw ApiException.conflict("PROJECT_ARCHIVED", "Restore the project first");
        }
        if (service.getStatus() != ServiceStatus.ACTIVE) {
            service.restore();
            audit.record(access.userId(), "service.restored", "service", serviceId, Map.of());
        }
        services.flush();
        return get(access, serviceId);
    }

    @Transactional
    public void delete(OrgAccess access, UUID serviceId) {
        MonitoredService service = requireLive(access.organizationId(), serviceId);
        service.softDelete(clock.instant());
        events.publishEvent(new ProjectEvents.ServiceDeactivated(access.organizationId(), service.getProjectId(),
                serviceId, true, access.userId()));
        audit.record(access.userId(), "service.deleted", "service", serviceId, Map.of("name", service.getName()));
    }

    /** For other modules: a live (not deleted) service of the organization, or 404. */
    @Transactional(readOnly = true)
    public MonitoredService requireLive(UUID organizationId, UUID serviceId) {
        return services.findLive(organizationId, serviceId).orElseThrow(ServiceCatalogService::notFound);
    }

    /** Deleting a project soft-deletes its services in the same transaction. */
    @EventListener
    public void onProjectDeactivated(ProjectEvents.ProjectDeactivated event) {
        if (event.deleted()) {
            services.softDeleteByProject(event.organizationId(), event.projectId(), clock.instant());
        }
    }

    private static ServiceView mapView(ResultSet rs, int rowNum) throws SQLException {
        String integrationType = rs.getString("integration_type");
        ServiceView.Connection connection = new ServiceView.Connection(
                integrationType == null ? null : integrationType.toUpperCase(Locale.ROOT),
                rs.getString("connection_status") == null ? "NOT_CONNECTED"
                        : rs.getString("connection_status").toUpperCase(Locale.ROOT),
                toInstant(rs.getTimestamp("first_telemetry_at")),
                toInstant(rs.getTimestamp("last_telemetry_at")),
                rs.getString("last_error"));
        return new ServiceView(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("project_name"),
                rs.getString("name"),
                rs.getString("description"),
                Technology.valueOf(rs.getString("technology").toUpperCase(Locale.ROOT)),
                ServiceStatus.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
                connection,
                toInstant(rs.getTimestamp("created_at")));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static ApiException notFound() {
        return ApiException.notFound("SERVICE_NOT_FOUND", "Service not found");
    }

    private static ApiException nameTaken() {
        return ApiException.conflict("SERVICE_NAME_TAKEN", "A service with this name already exists in the project");
    }
}
