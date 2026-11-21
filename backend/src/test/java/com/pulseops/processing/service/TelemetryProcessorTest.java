package com.pulseops.processing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.pulseops.common.tenancy.TenantContext;
import com.pulseops.integration.domain.IntegrationType;
import com.pulseops.integration.service.TelemetryReceptionRecorder;
import com.pulseops.processing.model.EventType;
import com.pulseops.processing.model.IngestBatch;
import com.pulseops.processing.model.TelemetryEvent;
import com.pulseops.processing.store.TelemetryStore;
import com.pulseops.testsupport.TestProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class TelemetryProcessorTest {

    private final TelemetryStore store = mock(TelemetryStore.class);
    private final TelemetryReceptionRecorder reception = mock(TelemetryReceptionRecorder.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final TelemetryProcessor processor = new TelemetryProcessor(TestProperties.defaults(), store, reception, tx);

    private final UUID orgId = UUID.randomUUID();
    private final UUID serviceId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-21T12:00:00Z");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void runTransactionsInline() {
        doAnswer(inv -> {
            assertThat(TenantContext.organizationId()).as("tenant bound before the transaction").isEqualTo(orgId);
            ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
    }

    @Test
    void storesEventsMetricsAndErrorLogsThenMarksConnected() {
        IngestBatch batch = batch(List.of(
                new TelemetryEvent(EventType.PERFORMANCE, "GET /a", now, Map.of(),
                        new TelemetryEvent.Http("GET", "/a", 200, 12), null, null, null, null),
                new TelemetryEvent(EventType.ERROR, "Boom", now, Map.of(), null, null,
                        new TelemetryEvent.Failure("X", "boom", null), null, null)));

        processor.process(batch);

        InOrder order = inOrder(store, reception);
        order.verify(store).saveEvents(batch);
        order.verify(store).mergeApiMetrics(eq(batch), argThat(aggs -> aggs.size() == 1));
        order.verify(store).saveErrorLogs(eq(batch), argThat(errors -> errors.size() == 1));
        order.verify(reception).recordReceived(orgId, serviceId, IntegrationType.REST_API, now);
        assertThat(processor.stats().processed()).isEqualTo(1);
        assertThat(TenantContext.organizationId()).as("context cleared afterwards").isNull();
    }

    @Test
    void storageFailureIsCountedAndDoesNotMarkConnected() {
        doThrow(new IllegalStateException("db down")).when(store).saveEvents(any());

        processor.process(batch(List.of(new TelemetryEvent(EventType.CUSTOM, "x", now, Map.of(), null, null, null,
                null, null))));

        assertThat(processor.stats().failed()).isEqualTo(1);
        verify(reception, never()).recordReceived(any(), any(), any(), any());
        verify(store, never()).saveErrorLogs(any(), anyList());
        assertThat(TenantContext.organizationId()).isNull();
    }

    @Test
    void submitIsRefusedUntilStarted() {
        assertThat(processor.submit(batch(List.of()))).isFalse();
    }

    private IngestBatch batch(List<TelemetryEvent> events) {
        return new IngestBatch(UUID.randomUUID(), orgId, UUID.randomUUID(), serviceId, UUID.randomUUID(),
                IntegrationType.REST_API, now, events);
    }
}
