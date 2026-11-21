package com.pulseops.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.pulseops.appservice.domain.MonitoredService;
import com.pulseops.appservice.domain.Technology;
import com.pulseops.appservice.service.ServiceCatalogService;
import com.pulseops.audit.service.AuditService;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.tenancy.Permissions;
import com.pulseops.credential.repository.IntegrationCredentialRepository;
import com.pulseops.integration.domain.ConnectionStatus;
import com.pulseops.integration.domain.IntegrationConnection;
import com.pulseops.integration.domain.IntegrationType;
import com.pulseops.integration.repository.IntegrationConnectionRepository;
import com.pulseops.project.service.ProjectEvents;
import com.pulseops.testsupport.MutableClock;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class IntegrationServiceTest {

    private final IntegrationConnectionRepository connections = mock(IntegrationConnectionRepository.class);
    private final IntegrationCredentialRepository credentials = mock(IntegrationCredentialRepository.class);
    private final ServiceCatalogService services = mock(ServiceCatalogService.class);
    private final MutableClock clock = MutableClock.startingAt("2026-03-01T10:00:00Z");
    private final IntegrationService integration = new IntegrationService(connections, credentials, services,
            mock(AuditService.class), clock);

    private final UUID orgId = UUID.randomUUID();
    private final OrgAccess access = new OrgAccess(orgId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "ADMIN", Set.of(Permissions.PROJECT_MANAGE));
    private final MonitoredService service = new MonitoredService(orgId, UUID.randomUUID(), "Orders", null,
            Technology.JAVA_SPRING_BOOT);

    @Test
    void serviceWithoutConnectionIsNotConnected() {
        when(services.requireLive(orgId, service.getId())).thenReturn(service);
        when(connections.findForService(orgId, service.getId())).thenReturn(Optional.empty());

        var view = integration.status(access, service.getId());

        assertThat(view.status()).isEqualTo(ConnectionStatus.NOT_CONNECTED);
        assertThat(view.receivingNow()).isFalse();
    }

    @Test
    void connectedButSilentServiceIsNotReportedAsReceiving() {
        IntegrationConnection c = new IntegrationConnection(orgId, service.getId(), IntegrationType.REST_API);
        ReflectionTestUtils.setField(c, "status", ConnectionStatus.CONNECTED);
        ReflectionTestUtils.setField(c, "lastTelemetryAt", clock.instant().minus(Duration.ofHours(1)));
        when(services.requireLive(orgId, service.getId())).thenReturn(service);
        when(connections.findForService(orgId, service.getId())).thenReturn(Optional.of(c));

        var view = integration.status(access, service.getId());

        assertThat(view.status()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(view.receivingNow()).isFalse();
        assertThat(view.nextStep()).contains("No telemetry");
    }

    @Test
    void waitingWithoutKeysTellsTheUserToCreateOne() {
        when(credentials.countUsableFor(any(), any(), any(), any())).thenReturn(0L);
        assertThat(IntegrationService.nextStep(ConnectionStatus.WAITING_FOR_DATA, 0, false))
                .contains("Create an integration key");
        assertThat(IntegrationService.nextStep(ConnectionStatus.WAITING_FOR_DATA, 1, false))
                .contains("Start your application");
    }

    @Test
    void deactivatingAServiceDisablesIngestion() {
        IntegrationConnection c = new IntegrationConnection(orgId, service.getId(), IntegrationType.REST_API);
        when(connections.findForService(orgId, service.getId())).thenReturn(Optional.of(c));

        integration.onServiceDeactivated(new ProjectEvents.ServiceDeactivated(orgId, service.getProjectId(),
                service.getId(), false, access.userId()));

        assertThat(c.getStatus()).isEqualTo(ConnectionStatus.DISABLED);
    }
}
