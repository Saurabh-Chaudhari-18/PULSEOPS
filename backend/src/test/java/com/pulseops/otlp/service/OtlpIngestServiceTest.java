package com.pulseops.otlp.service;

import static com.pulseops.testsupport.OtlpTestData.javaAgentResource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.pulseops.common.error.ApiException;
import com.pulseops.common.tenancy.TenantContext;
import com.pulseops.credential.service.CredentialAuthenticator;
import com.pulseops.credential.service.CredentialAuthenticator.AuthenticatedKey;
import com.pulseops.ingest.service.IngestRateLimiter;
import com.pulseops.ingest.service.IngestTargetResolver;
import com.pulseops.integration.domain.IntegrationType;
import com.pulseops.otlp.decode.OtlpDecoder;
import com.pulseops.processing.model.IngestBatch;
import com.pulseops.processing.service.TelemetryProcessor;
import com.pulseops.testsupport.MutableClock;
import com.pulseops.testsupport.OtlpTestData;
import com.pulseops.testsupport.TestProperties;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.resource.v1.Resource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class OtlpIngestServiceTest {

    private static final String RAW_KEY = "po_test";
    private static final Instant START = Instant.parse("2026-11-21T11:59:00Z");

    private final CredentialAuthenticator authenticator = mock(CredentialAuthenticator.class);
    private final IngestTargetResolver targets = mock(IngestTargetResolver.class);
    private final TelemetryProcessor processor = mock(TelemetryProcessor.class);
    private final MutableClock clock = MutableClock.startingAt("2026-11-21T12:00:00Z");
    private final OtlpIngestService otlp = new OtlpIngestService(authenticator,
            new IngestRateLimiter(TestProperties.defaults(), clock), targets, new OtlpDecoder(clock), processor, clock);

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID checkout = UUID.randomUUID();
    private final AuthenticatedKey projectKey = new AuthenticatedKey(UUID.randomUUID(), orgId, projectId, null);
    private final AuthenticatedKey serviceKey = new AuthenticatedKey(UUID.randomUUID(), orgId, projectId, checkout);

    @BeforeEach
    void setUp() {
        when(processor.remainingCapacity()).thenReturn(100);
        when(processor.submit(any())).thenReturn(true);
        when(targets.resolve(eq(serviceKey), isNull(), eq(false))).thenReturn(checkout);
        when(targets.resolve(projectKey, "checkout", false)).thenReturn(checkout);
        when(targets.resolve(projectKey, "ghost", false)).thenThrow(ApiException.notFound("UNKNOWN_SERVICE",
                "No service named 'ghost' in this project; create it first"));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static byte[] traceBody(Resource... resources) {
        return OtlpTestData.traces(java.util.Arrays.stream(resources)
                .map(r -> OtlpTestData.orderRequest(r, START))
                .toArray(io.opentelemetry.proto.trace.v1.ResourceSpans[]::new)).toByteArray();
    }

    private IngestBatch onlySubmittedBatch() {
        ArgumentCaptor<IngestBatch> captor = ArgumentCaptor.forClass(IngestBatch.class);
        verify(processor).submit(captor.capture());
        return captor.getValue();
    }

    @Test
    void serviceScopedKeyStoresSpansAndDerivesRequestsDbCallsAndExceptions() {
        when(authenticator.authenticate(RAW_KEY)).thenReturn(serviceKey);

        ExportTraceServiceResponse response = otlp.traces(RAW_KEY,
                traceBody(javaAgentResource("whatever-the-app-calls-itself")), null);

        assertThat(response.hasPartialSuccess()).isFalse();
        IngestBatch batch = onlySubmittedBatch();
        assertThat(batch.organizationId()).isEqualTo(orgId);
        assertThat(batch.serviceId()).isEqualTo(checkout);
        assertThat(batch.credentialId()).isEqualTo(serviceKey.credentialId());
        assertThat(batch.source()).isEqualTo(IntegrationType.JAVA_AGENT);
        assertThat(batch.events()).isEmpty();
        assertThat(batch.spans()).hasSize(2);
        assertThat(batch.derivedRequests()).singleElement().satisfies(r -> {
            assertThat(r.http().route()).isEqualTo("/orders/{id}");
            assertThat(r.http().statusCode()).isEqualTo(500);
        });
        assertThat(batch.dbOperations()).singleElement().satisfies(d -> {
            assertThat(d.operationType()).isEqualTo("SELECT");
            assertThat(d.operation()).isEqualTo("SELECT * FROM orders WHERE id = ?");
            assertThat(d.tableName()).isEqualTo("orders");
        });
        assertThat(batch.logs()).singleElement().satisfies(l -> {
            assertThat(l.level()).isEqualTo("ERROR");
            assertThat(l.message()).startsWith("java.sql.SQLTransientConnectionException");
        });
        assertThat(batch.requestsForAggregation()).hasSize(1);
        verify(authenticator).markUsed(serviceKey);
    }

    @Test
    void gzipBodiesAreAccepted() {
        when(authenticator.authenticate(RAW_KEY)).thenReturn(serviceKey);
        otlp.traces(RAW_KEY, OtlpTestData.gzip(traceBody(javaAgentResource("checkout"))), "gzip");
        assertThat(onlySubmittedBatch().spans()).hasSize(2);
    }

    @Test
    void projectWideKeyRejectsUnknownServicesThroughPartialSuccess() {
        when(authenticator.authenticate(RAW_KEY)).thenReturn(projectKey);

        ExportTraceServiceResponse response = otlp.traces(RAW_KEY,
                traceBody(javaAgentResource("checkout"), javaAgentResource("ghost")), null);

        assertThat(response.getPartialSuccess().getRejectedSpans()).isEqualTo(2);
        assertThat(response.getPartialSuccess().getErrorMessage())
                .contains("2 spans were not attributed to a service")
                .contains("No service named 'ghost'");
        assertThat(onlySubmittedBatch().serviceId()).isEqualTo(checkout);
    }

    @Test
    void resourcesOfTheSameServiceShareOneBatch() {
        when(authenticator.authenticate(RAW_KEY)).thenReturn(projectKey);

        otlp.traces(RAW_KEY, traceBody(javaAgentResource("checkout"), javaAgentResource("checkout")), null);

        // Same span IDs twice: storage deduplicates, but both are submitted in one batch.
        assertThat(onlySubmittedBatch().spans()).hasSize(4);
        verify(targets, times(1)).resolve(projectKey, "checkout", false);
    }

    @Test
    void whenNothingCanBeAttributedTheRequestFailsWithTheReason() {
        when(authenticator.authenticate(RAW_KEY)).thenReturn(projectKey);

        assertThatThrownBy(() -> otlp.traces(RAW_KEY, traceBody(javaAgentResource("ghost")), null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("UNKNOWN_SERVICE");
                    assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
                });
        verify(processor, never()).submit(any());
        verify(authenticator, never()).markUsed(any());
    }

    @Test
    void projectLevelProblemsFailTheWholeRequest() {
        when(authenticator.authenticate(RAW_KEY)).thenReturn(projectKey);
        when(targets.resolve(projectKey, "checkout", false))
                .thenThrow(ApiException.forbidden("PROJECT_ARCHIVED", "The key's project is archived"));

        assertThatThrownBy(() -> otlp.traces(RAW_KEY, traceBody(javaAgentResource("checkout")), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("PROJECT_ARCHIVED"));
        verify(processor, never()).submit(any());
    }

    @Test
    void serviceScopedKeyProblemsFailTheWholeRequest() {
        when(authenticator.authenticate(RAW_KEY)).thenReturn(serviceKey);
        when(targets.resolve(eq(serviceKey), isNull(), eq(false)))
                .thenThrow(ApiException.forbidden("INGESTION_DISABLED", "Ingestion is disabled"));

        assertThatThrownBy(() -> otlp.traces(RAW_KEY, traceBody(javaAgentResource("checkout")), null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("INGESTION_DISABLED"));
    }

    @Test
    void malformedProtobufIsRejected() {
        when(authenticator.authenticate(RAW_KEY)).thenReturn(serviceKey);
        assertThatThrownBy(() -> otlp.traces(RAW_KEY, new byte[]{(byte) 0xFF, (byte) 0xFF, 0x01}, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("MALFORMED_PROTOBUF"));
    }

    @Test
    void fullQueueRefusesTheRequestBeforeSubmittingAnything() {
        when(authenticator.authenticate(RAW_KEY)).thenReturn(serviceKey);
        when(processor.remainingCapacity()).thenReturn(0);

        assertThatThrownBy(() -> otlp.traces(RAW_KEY, traceBody(javaAgentResource("checkout")), null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("INGEST_BUSY");
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
        verify(processor, never()).submit(any());
    }

    @Test
    void authenticationFailuresPropagate() {
        when(authenticator.authenticate(RAW_KEY)).thenThrow(ApiException.unauthorized("INVALID_CREDENTIAL", "x"));
        assertThatThrownBy(() -> otlp.traces(RAW_KEY, traceBody(javaAgentResource("checkout")), null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("INVALID_CREDENTIAL"));
        verify(targets, never()).resolve(any(), any(), eq(false));
    }

    @Test
    void logsBecomeLogBatches() {
        when(authenticator.authenticate(RAW_KEY)).thenReturn(serviceKey);

        ExportLogsServiceResponse response = otlp.logs(RAW_KEY,
                OtlpTestData.logs(javaAgentResource("checkout"), START).toByteArray(), null);

        assertThat(response.hasPartialSuccess()).isFalse();
        IngestBatch batch = onlySubmittedBatch();
        assertThat(batch.logs()).singleElement().satisfies(l -> assertThat(l.level()).isEqualTo("WARN"));
        assertThat(batch.spans()).isEmpty();
    }

    @Test
    void outOfWindowLogsAreReportedAsRejected() {
        when(authenticator.authenticate(RAW_KEY)).thenReturn(serviceKey);

        ExportLogsServiceResponse response = otlp.logs(RAW_KEY,
                OtlpTestData.logs(javaAgentResource("checkout"), Instant.parse("2026-10-01T00:00:00Z")).toByteArray(),
                null);

        assertThat(response.getPartialSuccess().getRejectedLogRecords()).isEqualTo(1);
        assertThat(response.getPartialSuccess().getErrorMessage()).contains("timestamps outside the accepted window");
        verify(processor, never()).submit(any());
    }

    @Test
    void metricsBecomeMetricPointBatches() {
        when(authenticator.authenticate(RAW_KEY)).thenReturn(serviceKey);

        ExportMetricsServiceResponse response = otlp.metrics(RAW_KEY,
                OtlpTestData.metrics(javaAgentResource("checkout"), Instant.parse("2026-11-21T11:58:00Z"))
                        .toByteArray(), null);

        assertThat(response.hasPartialSuccess()).isFalse();
        assertThat(onlySubmittedBatch().metricPoints()).hasSize(2);
    }

    @Test
    void sourceDetectsTheJavaAgent() {
        assertThat(OtlpIngestService.sourceOf(Map.of("telemetry.distro.name", "opentelemetry-java-instrumentation")))
                .isEqualTo(IntegrationType.JAVA_AGENT);
        assertThat(OtlpIngestService.sourceOf(Map.of("telemetry.auto.version", "1.32.0",
                "telemetry.sdk.language", "java"))).isEqualTo(IntegrationType.JAVA_AGENT);
        assertThat(OtlpIngestService.sourceOf(Map.of("telemetry.sdk.language", "python")))
                .isEqualTo(IntegrationType.OTLP);
        assertThat(OtlpIngestService.sourceOf(Map.of())).isEqualTo(IntegrationType.OTLP);
    }

    @Test
    void messageCombinesBothReasons() {
        String message = OtlpIngestService.message("spans", 3, 2, ApiException.notFound("UNKNOWN_SERVICE", "nope"));
        assertThat(message).isEqualTo("3 spans had invalid IDs or timestamps outside the accepted window "
                + "(7 days old to 5 minutes ahead); 2 spans were not attributed to a service: nope");
        assertThat(OtlpIngestService.message("spans", 0, 0, null)).isEmpty();
    }
}
