package com.pulseops.appservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.pulseops.appservice.domain.MonitoredService;
import com.pulseops.appservice.domain.Technology;
import com.pulseops.appservice.repository.MonitoredServiceRepository;
import com.pulseops.audit.service.AuditService;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.tenancy.Permissions;
import com.pulseops.project.domain.Environment;
import com.pulseops.project.domain.Project;
import com.pulseops.project.domain.ProjectDetails;
import com.pulseops.project.service.ProjectEvents;
import com.pulseops.project.service.ProjectService;
import com.pulseops.testsupport.MutableClock;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

class ServiceCatalogServiceTest {

    private final MonitoredServiceRepository services = mock(MonitoredServiceRepository.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final MutableClock clock = MutableClock.startingAt("2026-03-01T10:00:00Z");
    private final ServiceCatalogService catalog = new ServiceCatalogService(services, projectService,
            mock(AuditService.class), events, mock(JdbcTemplate.class), clock);

    private final UUID orgId = UUID.randomUUID();
    private final OrgAccess access = new OrgAccess(orgId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "ADMIN", Set.of(Permissions.PROJECT_MANAGE));

    @Test
    void cannotAddServicesToAnArchivedProject() {
        Project project = project();
        project.archive();
        when(projectService.requireLive(orgId, project.getId())).thenReturn(project);

        assertThatThrownBy(() -> catalog.create(access, project.getId(), "Orders", null, Technology.GO))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("PROJECT_ARCHIVED");
        verify(services, never()).save(any());
    }

    @Test
    void duplicateServiceNameInProjectIsRejected() {
        Project project = project();
        when(projectService.requireLive(orgId, project.getId())).thenReturn(project);
        when(services.nameTaken(project.getId(), "Orders")).thenReturn(true);

        assertThatThrownBy(() -> catalog.create(access, project.getId(), " Orders ", null, null))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("SERVICE_NAME_TAKEN");
    }

    @Test
    void deletingAServiceAnnouncesDeactivation() {
        MonitoredService svc = new MonitoredService(orgId, UUID.randomUUID(), "Orders", null, Technology.GO);
        when(services.findLive(orgId, svc.getId())).thenReturn(Optional.of(svc));

        catalog.delete(access, svc.getId());

        verify(events).publishEvent(new ProjectEvents.ServiceDeactivated(orgId, svc.getProjectId(), svc.getId(),
                true, access.userId()));
        assertThat(svc.isActive()).isFalse();
    }

    @Test
    void projectDeletionCascadesToServices() {
        UUID projectId = UUID.randomUUID();

        catalog.onProjectDeactivated(new ProjectEvents.ProjectDeactivated(orgId, projectId, true, access.userId()));

        verify(services).softDeleteByProject(orgId, projectId, clock.instant());
    }

    @Test
    void projectArchivalDoesNotDeleteServices() {
        catalog.onProjectDeactivated(new ProjectEvents.ProjectDeactivated(orgId, UUID.randomUUID(), false,
                access.userId()));

        verify(services, never()).softDeleteByProject(any(), any(), any());
    }

    private Project project() {
        return new Project(orgId, new ProjectDetails("Shop", null, null, Environment.PRODUCTION, null, null),
                access.userId());
    }
}
