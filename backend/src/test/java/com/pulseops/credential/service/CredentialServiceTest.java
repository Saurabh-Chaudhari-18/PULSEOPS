package com.pulseops.credential.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.pulseops.appservice.domain.MonitoredService;
import com.pulseops.appservice.domain.Technology;
import com.pulseops.appservice.service.ServiceCatalogService;
import com.pulseops.audit.service.AuditService;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.tenancy.Permissions;
import com.pulseops.credential.domain.CredentialStatus;
import com.pulseops.credential.domain.IntegrationCredential;
import com.pulseops.credential.domain.IntegrationKeys;
import com.pulseops.credential.repository.IntegrationCredentialRepository;
import com.pulseops.project.domain.Environment;
import com.pulseops.project.domain.Project;
import com.pulseops.project.domain.ProjectDetails;
import com.pulseops.project.service.ProjectEvents;
import com.pulseops.project.service.ProjectService;
import com.pulseops.testsupport.MutableClock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CredentialServiceTest {

    private final IntegrationCredentialRepository credentials = mock(IntegrationCredentialRepository.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final ServiceCatalogService services = mock(ServiceCatalogService.class);
    private final MutableClock clock = MutableClock.startingAt("2026-03-01T10:00:00Z");
    private final CredentialService service = new CredentialService(credentials, projects, services,
            mock(AuditService.class), clock);

    private final UUID orgId = UUID.randomUUID();
    private final OrgAccess access = new OrgAccess(orgId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "ADMIN", Set.of(Permissions.CREDENTIAL_MANAGE));
    private Project project;

    @BeforeEach
    void setUp() {
        project = new Project(orgId, new ProjectDetails("Shop", null, null, Environment.PRODUCTION, null, null),
                access.userId());
        when(projects.requireLive(orgId, project.getId())).thenReturn(project);
        when(credentials.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createdKeyIsReturnedOnceAndMatchesTheStoredHash() {
        CredentialView.Issued issued = service.create(access, project.getId(), null, " Production key ", 90);

        ArgumentCaptor<IntegrationCredential> saved = ArgumentCaptor.forClass(IntegrationCredential.class);
        verify(credentials).save(saved.capture());
        assertThat(IntegrationKeys.looksValid(issued.key())).isTrue();
        assertThat(saved.getValue().getSecretHash()).isEqualTo(IntegrationKeys.hash(issued.key()));
        assertThat(issued.credential().name()).isEqualTo("Production key");
        assertThat(issued.credential().expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(90)));
        assertThat(issued.credential().toString()).doesNotContain(issued.key());
    }

    @Test
    void serviceFromAnotherProjectIsRejected() {
        MonitoredService foreign = new MonitoredService(orgId, UUID.randomUUID(), "Other", null, Technology.GO);
        when(services.requireLive(orgId, foreign.getId())).thenReturn(foreign);

        assertThatThrownBy(() -> service.create(access, project.getId(), foreign.getId(), "k", null))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("SERVICE_NOT_IN_PROJECT");
        verify(credentials, never()).save(any());
    }

    @Test
    void archivedProjectCannotGetNewKeys() {
        project.archive();
        assertThatThrownBy(() -> service.create(access, project.getId(), null, "k", null))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("PROJECT_ARCHIVED");
    }

    @Test
    void expiryOutsideAllowedRangeIsRejected() {
        assertThatThrownBy(() -> CredentialService.expiryFrom(clock.instant(), 0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> CredentialService.expiryFrom(clock.instant(), 366)).isInstanceOf(ApiException.class);
        assertThat(CredentialService.expiryFrom(clock.instant(), null)).isNull();
    }

    @Test
    void rotationWithoutGraceRevokesTheOldKeyImmediately() {
        IntegrationCredential old = existing();

        CredentialView.Issued issued = service.rotate(access, old.getId(), 0);

        assertThat(old.effectiveStatus(clock.instant())).isEqualTo(CredentialStatus.REVOKED);
        assertThat(issued.credential().rotatedFromId()).isEqualTo(old.getId());
        assertThat(issued.key()).isNotBlank();
    }

    @Test
    void rotationWithGraceKeepsOldKeyWorkingUntilDeadline() {
        IntegrationCredential old = existing();

        service.rotate(access, old.getId(), 24);

        assertThat(old.isUsable(clock.instant().plus(Duration.ofHours(23)))).isTrue();
        assertThat(old.isUsable(clock.instant().plus(Duration.ofHours(24)))).isFalse();
    }

    @Test
    void revokedKeyCannotBeRotated() {
        IntegrationCredential old = existing();
        old.revoke(clock.instant());

        assertThatThrownBy(() -> service.rotate(access, old.getId(), 0))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("CREDENTIAL_NOT_ACTIVE");
    }

    @Test
    void deletingAProjectRevokesItsKeys() {
        service.onProjectDeactivated(new ProjectEvents.ProjectDeactivated(orgId, project.getId(), true,
                access.userId()));
        verify(credentials).revokeAllForProject(orgId, project.getId(), clock.instant());
    }

    @Test
    void archivingAProjectKeepsItsKeys() {
        service.onProjectDeactivated(new ProjectEvents.ProjectDeactivated(orgId, project.getId(), false,
                access.userId()));
        verify(credentials, never()).revokeAllForProject(any(), any(), any());
    }

    private IntegrationCredential existing() {
        IntegrationCredential c = new IntegrationCredential(orgId, project.getId(), null, "prod",
                IntegrationKeys.generate(), null, null, access.userId());
        when(credentials.findInOrganization(orgId, c.getId())).thenReturn(Optional.of(c));
        return c;
    }
}
