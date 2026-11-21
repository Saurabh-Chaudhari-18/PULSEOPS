package com.pulseops.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseops.appservice.domain.MonitoredService;
import com.pulseops.appservice.domain.Technology;
import com.pulseops.appservice.repository.MonitoredServiceRepository;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.error.ValidationFailedException;
import com.pulseops.common.tenancy.TenantContext;
import com.pulseops.credential.service.CredentialAuthenticator;
import com.pulseops.credential.service.CredentialAuthenticator.AuthenticatedKey;
import com.pulseops.integration.domain.ConnectionStatus;
import com.pulseops.integration.domain.IntegrationConnection;
import com.pulseops.integration.domain.IntegrationType;
import com.pulseops.integration.repository.IntegrationConnectionRepository;
import com.pulseops.integration.service.TelemetryReceptionRecorder;
import com.pulseops.processing.model.IngestBatch;
import com.pulseops.processing.service.TelemetryProcessor;
import com.pulseops.project.domain.Environment;
import com.pulseops.project.domain.Project;
import com.pulseops.project.domain.ProjectDetails;
import com.pulseops.project.repository.ProjectRepository;
import com.pulseops.testsupport.MutableClock;
import com.pulseops.testsupport.TestProperties;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class IngestServiceTest {

    private final CredentialAuthenticator authenticator = mock(CredentialAuthenticator.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final MonitoredServiceRepository services = mock(MonitoredServiceRepository.class);
    private final IntegrationConnectionRepository connections = mock(IntegrationConnectionRepository.class);
    private final TelemetryReceptionRecorder reception = mock(TelemetryReceptionRecorder.class);
    private final TelemetryProcessor processor = mock(TelemetryProcessor.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final MutableClock clock = MutableClock.startingAt("2026-11-21T12:00:00Z");
    private final IngestService ingest = new IngestService(authenticator,
            new IngestTargetResolver(projects, services, connections, tx),
            new IngestRateLimiter(TestProperties.defaults(), clock), reception, processor, new ObjectMapper(), clock,
            TestProperties.defaults());

    private final UUID orgId = UUID.randomUUID();
    private Project project;
    private MonitoredService orders;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        project = new Project(orgId, new ProjectDetails("Shop", null, null, Environment.PRODUCTION, null, null), null);
        orders = new MonitoredService(orgId, project.getId(), "Orders", null, Technology.JAVA_SPRING_BOOT);
        doAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null)).when(tx).execute(any());
        when(projects.findLive(orgId, project.getId())).thenReturn(Optional.of(project));
        when(services.findLiveByName(orgId, project.getId(), "Orders")).thenReturn(Optional.of(orders));
        when(services.findLive(orgId, orders.getId())).thenReturn(Optional.of(orders));
        when(connections.findForService(orgId, orders.getId())).thenReturn(Optional.empty());
        when(processor.submit(any())).thenReturn(true);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void validBatchIsQueuedForTheNamedServiceAndAnswered() {
        AuthenticatedKey key = projectKey();

        var accepted = ingest.accept("k", body("Orders", validEvent()));

        ArgumentCaptor<IngestBatch> batch = ArgumentCaptor.forClass(IngestBatch.class);
        verify(processor).submit(batch.capture());
        assertThat(batch.getValue().serviceId()).isEqualTo(orders.getId());
        assertThat(batch.getValue().organizationId()).isEqualTo(orgId);
        assertThat(batch.getValue().source()).isEqualTo(IntegrationType.REST_API);
        assertThat(accepted.accepted()).isEqualTo(1);
        verify(authenticator).markUsed(key);
        assertThat(TenantContext.organizationId()).as("context restored after the call").isNull();
    }

    @Test
    void projectWideKeyMustNameAService() {
        projectKey();
        assertCode(() -> ingest.accept("k", body(null, validEvent())), "SERVICE_REQUIRED");
    }

    @Test
    void servicesAreNeverAutoCreated() {
        projectKey();
        when(services.findLiveByName(orgId, project.getId(), "Ghost")).thenReturn(Optional.empty());
        assertCode(() -> ingest.accept("k", body("Ghost", validEvent())), "UNKNOWN_SERVICE");
        verify(processor, never()).submit(any());
    }

    @Test
    void serviceScopedKeyCannotWriteToAnotherService() {
        when(authenticator.authenticate("k")).thenReturn(
                new AuthenticatedKey(UUID.randomUUID(), orgId, project.getId(), orders.getId()));
        assertCode(() -> ingest.accept("k", body("Payments", validEvent())), "SERVICE_SCOPE_MISMATCH");
    }

    @Test
    void archivedProjectAndDisabledIngestionAreRefused() {
        projectKey();
        IntegrationConnection disabled = new IntegrationConnection(orgId, orders.getId(), IntegrationType.REST_API);
        ReflectionTestUtils.setField(disabled, "status", ConnectionStatus.DISABLED);
        when(connections.findForService(orgId, orders.getId())).thenReturn(Optional.of(disabled));
        assertCode(() -> ingest.accept("k", body("Orders", validEvent())), "INGESTION_DISABLED");

        project.archive();
        assertCode(() -> ingest.accept("k", body("Orders", validEvent())), "PROJECT_ARCHIVED");
    }

    @Test
    void invalidEventsAreRejectedAndRecordedOnTheConnection() {
        projectKey();
        String bad = "{\"type\":\"performance\",\"name\":\"x\",\"timestamp\":\"not-a-time\",\"durationMs\":5}";

        assertThatThrownBy(() -> ingest.accept("k", body("Orders", bad)))
                .isInstanceOf(ValidationFailedException.class)
                .satisfies(ex -> assertThat(((ValidationFailedException) ex).violations())
                        .extracting("field").contains("events[0].timestamp"));
        verify(reception).recordRejected(eq(orgId), eq(orders.getId()), eq(IntegrationType.REST_API), anyString());
        verify(processor, never()).submit(any());
    }

    @Test
    void malformedJsonIsRejectedBeforeAnyTenantWork() {
        projectKey();
        assertCode(() -> ingest.accept("k", "{not json".getBytes(StandardCharsets.UTF_8)), "MALFORMED_JSON");
        verify(projects, never()).findLive(any(), any());
    }

    @Test
    void fullQueueAnswersBusy() {
        projectKey();
        when(processor.submit(any())).thenReturn(false);
        assertCode(() -> ingest.accept("k", body("Orders", validEvent())), "INGEST_BUSY");
        verify(authenticator, never()).markUsed(any());
    }

    @Test
    void perKeyRateLimitApplies() {
        projectKey();
        int limit = TestProperties.defaults().ingest().batchesPerMinutePerKey();
        for (int i = 0; i < limit; i++) {
            ingest.accept("k", body("Orders", validEvent()));
        }
        assertCode(() -> ingest.accept("k", body("Orders", validEvent())), "RATE_LIMITED");
    }

    private AuthenticatedKey projectKey() {
        AuthenticatedKey key = new AuthenticatedKey(UUID.randomUUID(), orgId, project.getId(), null);
        when(authenticator.authenticate("k")).thenReturn(key);
        return key;
    }

    private static String validEvent() {
        return "{\"type\":\"performance\",\"name\":\"GET /orders/{id}\",\"timestamp\":\"2026-11-21T11:59:00Z\","
                + "\"httpMethod\":\"GET\",\"route\":\"/orders/{id}\",\"statusCode\":200,\"durationMs\":42.5}";
    }

    private static byte[] body(String service, String eventJson) {
        String svc = service == null ? "" : "\"service\":\"" + service + "\",";
        return ("{" + svc + "\"events\":[" + eventJson + "]}").getBytes(StandardCharsets.UTF_8);
    }

    private static void assertCode(ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);
    }
}
