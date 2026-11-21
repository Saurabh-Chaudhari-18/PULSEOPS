package com.pulseops.project.service;

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

import com.pulseops.audit.service.AuditService;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.tenancy.Permissions;
import com.pulseops.project.domain.Environment;
import com.pulseops.project.domain.Project;
import com.pulseops.project.domain.ProjectDetails;
import com.pulseops.project.repository.ProjectRepository;
import com.pulseops.testsupport.MutableClock;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

class ProjectServiceTest {

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final MutableClock clock = MutableClock.startingAt("2026-03-01T10:00:00Z");
    private final ProjectService service = new ProjectService(projects, mock(AuditService.class), events,
            mock(JdbcTemplate.class), clock);

    private final UUID orgId = UUID.randomUUID();
    private final OrgAccess access = new OrgAccess(orgId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "ADMIN", Set.of(Permissions.PROJECT_MANAGE));

    @Test
    void duplicateNameIsRejectedBeforeInsert() {
        when(projects.nameTaken(orgId, "Shop")).thenReturn(true);

        assertThatThrownBy(() -> service.create(access, details(" Shop ")))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("PROJECT_NAME_TAKEN");
        verify(projects, never()).save(any());
    }

    @Test
    void deleteRequiresTypingTheProjectName() {
        Project project = new Project(orgId, details("Shop"), access.userId());
        when(projects.findLive(orgId, project.getId())).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> service.delete(access, project.getId(), "Wrong"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("CONFIRMATION_MISMATCH");
        assertThat(project.isDeleted()).isFalse();
        verify(events, never()).publishEvent(any());
    }

    @Test
    void deleteSoftDeletesAndAnnouncesDeactivation() {
        Project project = new Project(orgId, details("Shop"), access.userId());
        when(projects.findLive(orgId, project.getId())).thenReturn(Optional.of(project));

        service.delete(access, project.getId(), "shop");

        assertThat(project.isDeleted()).isTrue();
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(
                new ProjectEvents.ProjectDeactivated(orgId, project.getId(), true, access.userId()));
    }

    @Test
    void projectOfAnotherOrganizationIsNotFound() {
        UUID foreignProject = UUID.randomUUID();
        when(projects.findLive(orgId, foreignProject)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireLive(orgId, foreignProject))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("PROJECT_NOT_FOUND");
    }

    private static ProjectDetails details(String name) {
        return new ProjectDetails(name, null, null, Environment.PRODUCTION, null, null);
    }
}
