package com.pulseops.processing.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.pulseops.integration.domain.IntegrationType;

/** A unit of asynchronous work: validated events for one service of one tenant. */
public record IngestBatch(
        UUID batchId,
        UUID organizationId,
        UUID projectId,
        UUID serviceId,
        UUID credentialId,
        IntegrationType source,
        Instant receivedAt,
        List<TelemetryEvent> events) {

    public IngestBatch {
        events = List.copyOf(events);
    }
}
