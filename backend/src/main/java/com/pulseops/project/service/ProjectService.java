package com.pulseops.project.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.pulseops.audit.service.AuditService;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.persistence.LikePatterns;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.web.PageQuery;
import com.pulseops.common.web.PageResponse;
import com.pulseops.project.domain.Environment;
import com.pulseops.project.domain.Project;
import com.pulseops.project.domain.ProjectDetails;
import com.pulseops.project.domain.ProjectStatus;
import com.pulseops.project.repository.ProjectRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Project lifecycle: create, list/search, update, archive/restore, soft delete. */
@Service
public class ProjectService {

    private static final String VIEW_SELECT = """
            SELECT p.id, p.name, p.description, p.tech_stack, p.environment, p.region, p.application_type,
                   p.status, p.created_at, p.updated_at,
                   count(s.id) AS service_count,
                   count(c.id) FILTER (WHERE c.status = 'connected') AS connected_count
            FROM projects p
            LEFT JOIN services s ON s.project_id = p.id AND s.deleted_at IS NULL
            LEFT JOIN integration_connections c ON c.service_id = s.id
            """;

    private final ProjectRepository projects;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProjectService(ProjectRepository projects, AuditService audit, ApplicationEventPublisher events,
                          JdbcTemplate jdbc, Clock clock) {
        this.projects = projects;
        this.audit = audit;
        this.events = events;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public ProjectView create(OrgAccess access, ProjectDetails requested) {
        ProjectDetails details = requested.normalized();
        if (projects.nameTaken(access.organizationId(), details.name())) {
            throw nameTaken();
        }
        Project project = projects.save(new Project(access.organizationId(), details, access.userId()));
        audit.record(access.userId(), "project.created", "project", project.getId(),
                Map.of("name", details.name(), "environment", details.environment().name()));
        projects.flush();
        return get(access, project.getId());
    }

    @Transactional(readOnly = true)
    public PageResponse<ProjectView> list(OrgAccess access, String search, ProjectStatus status, PageQuery page) {
        String pattern = LikePatterns.contains(search);
        String statusValue = status == null ? null : status.name().toLowerCase(Locale.ROOT);
        String where = """
                WHERE p.organization_id = ? AND p.deleted_at IS NULL
                  AND (?::text IS NULL OR p.status = ?)
                  AND (?::text IS NULL OR p.name ILIKE ? ESCAPE '\\')
                """;
        long total = jdbc.queryForObject("SELECT count(*) FROM projects p " + where, Long.class,
                access.organizationId(), statusValue, statusValue, pattern, pattern);
        List<ProjectView> rows = jdbc.query(VIEW_SELECT + where + " GROUP BY p.id ORDER BY lower(p.name) LIMIT ? OFFSET ?",
                ProjectService::mapView, access.organizationId(), statusValue, statusValue, pattern, pattern,
                page.size(), page.offset());
        return PageResponse.of(rows, page, total);
    }

    @Transactional(readOnly = true)
    public ProjectView get(OrgAccess access, UUID projectId) {
        List<ProjectView> rows = jdbc.query(
                VIEW_SELECT + " WHERE p.organization_id = ? AND p.id = ? AND p.deleted_at IS NULL GROUP BY p.id",
                ProjectService::mapView, access.organizationId(), projectId);
        if (rows.isEmpty()) {
            throw notFound();
        }
        return rows.get(0);
    }

    @Transactional
    public ProjectView update(OrgAccess access, UUID projectId, ProjectDetails patch) {
        Project project = load(access, projectId);
        ProjectDetails merged = project.details().merge(patch);
        if (patch.name() != null
                && projects.nameTakenByOther(access.organizationId(), merged.name(), projectId)) {
            throw nameTaken();
        }
        project.apply(merged);
        audit.record(access.userId(), "project.updated", "project", projectId, Map.of("name", merged.name()));
        projects.flush();
        return get(access, projectId);
    }

    @Transactional
    public ProjectView archive(OrgAccess access, UUID projectId) {
        Project project = load(access, projectId);
        if (project.getStatus() != ProjectStatus.ARCHIVED) {
            project.archive();
            events.publishEvent(new ProjectEvents.ProjectDeactivated(access.organizationId(), projectId, false,
                    access.userId()));
            audit.record(access.userId(), "project.archived", "project", projectId, Map.of());
        }
        projects.flush();
        return get(access, projectId);
    }

    @Transactional
    public ProjectView restore(OrgAccess access, UUID projectId) {
        Project project = load(access, projectId);
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            if (projects.nameTakenByOther(access.organizationId(), project.getName(), projectId)) {
                throw nameTaken();
            }
            project.restore();
            audit.record(access.userId(), "project.restored", "project", projectId, Map.of());
        }
        projects.flush();
        return get(access, projectId);
    }

    /** Soft delete: the project, its services and (via event) its credentials are deactivated. */
    @Transactional
    public void delete(OrgAccess access, UUID projectId, String confirmName) {
        Project project = load(access, projectId);
        if (confirmName == null || !project.getName().equalsIgnoreCase(confirmName.trim())) {
            throw ApiException.badRequest("CONFIRMATION_MISMATCH", "Type the project name to confirm deletion");
        }
        project.softDelete(clock.instant());
        events.publishEvent(new ProjectEvents.ProjectDeactivated(access.organizationId(), projectId, true,
                access.userId()));
        audit.record(access.userId(), "project.deleted", "project", projectId, Map.of("name", project.getName()));
    }

    /** For other modules: a live project of the current organization, or 404. */
    @Transactional(readOnly = true)
    public Project requireLive(UUID organizationId, UUID projectId) {
        return projects.findLive(organizationId, projectId).orElseThrow(ProjectService::notFound);
    }

    private Project load(OrgAccess access, UUID projectId) {
        return requireLive(access.organizationId(), projectId);
    }

    private static ProjectView mapView(ResultSet rs, int rowNum) throws SQLException {
        return new ProjectView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("tech_stack"),
                Environment.valueOf(rs.getString("environment").toUpperCase(Locale.ROOT)),
                rs.getString("region"),
                rs.getString("application_type"),
                ProjectStatus.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
                rs.getLong("service_count"),
                rs.getLong("connected_count"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static ApiException notFound() {
        return ApiException.notFound("PROJECT_NOT_FOUND", "Project not found");
    }

    private static ApiException nameTaken() {
        return ApiException.conflict("PROJECT_NAME_TAKEN", "A project with this name already exists");
    }
}
