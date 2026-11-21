package com.pulseops.processing.store;

import java.util.Collection;
import java.util.List;

import com.pulseops.processing.model.IngestBatch;
import com.pulseops.processing.model.TelemetryEvent;
import com.pulseops.processing.service.ApiMetricAggregator;

/**
 * Storage port for processed telemetry (ADR-007). The PostgreSQL implementation is the
 * default; an analytical store (e.g. ClickHouse) can implement this interface later
 * without changing validation, normalization or aggregation.
 *
 * <p>Implementations run inside a transaction bound to the batch's organization.</p>
 */
public interface TelemetryStore {

    /** Raw events, bounded later by retention policies. */
    void saveEvents(IngestBatch batch);

    /** Merges one-minute aggregates into existing rows for the same keys. */
    void mergeApiMetrics(IngestBatch batch, Collection<ApiMetricAggregator.Aggregate> aggregates);

    /** Error events also become ERROR log lines so they are searchable alongside logs. */
    void saveErrorLogs(IngestBatch batch, List<TelemetryEvent> errorEvents);
}
