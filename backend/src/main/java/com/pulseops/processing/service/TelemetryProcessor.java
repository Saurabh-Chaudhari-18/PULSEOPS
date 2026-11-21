package com.pulseops.processing.service;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.pulseops.common.config.PulseOpsProperties;
import com.pulseops.common.tenancy.TenantContext;
import com.pulseops.integration.service.TelemetryReceptionRecorder;
import com.pulseops.processing.model.EventType;
import com.pulseops.processing.model.IngestBatch;
import com.pulseops.processing.model.TelemetryEvent;
import com.pulseops.processing.store.TelemetryStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asynchronous telemetry processing (no Kafka in v1, per the hardware constraints).
 *
 * <p>Accepted batches go into a bounded in-memory queue; a small pool of workers stores each
 * batch in one transaction bound to the batch's organization (RLS applies), then records
 * telemetry reception for the connection status. When the queue is full, {@link #submit}
 * refuses and the API answers 503 so clients back off — memory is never unbounded.</p>
 *
 * <p>Delivery is at-most-once: batches still queued when the process stops abruptly are lost
 * (KNOWN_ISSUES). Graceful shutdown drains the queue for up to 20 seconds.</p>
 */
@Service
public class TelemetryProcessor implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelemetryProcessor.class);

    private final BlockingQueue<IngestBatch> queue;
    private final int workers;
    private final TelemetryStore store;
    private final TelemetryReceptionRecorder reception;
    private final TransactionTemplate tx;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();
    private ExecutorService pool;

    public TelemetryProcessor(PulseOpsProperties props, TelemetryStore store, TelemetryReceptionRecorder reception,
                              TransactionTemplate tx) {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, props.ingest().queueCapacity()));
        this.workers = Math.max(1, props.ingest().workerThreads());
        this.store = store;
        this.reception = reception;
        this.tx = tx;
    }

    /** @return false when the queue is full (caller must answer 503) or the processor is stopping */
    public boolean submit(IngestBatch batch) {
        return running.get() && queue.offer(batch);
    }

    /** Processes one batch synchronously; used by workers (and directly by tests). */
    public void process(IngestBatch batch) {
        TenantContext.clear();
        TenantContext.setOrganizationId(batch.organizationId());
        try {
            tx.executeWithoutResult(status -> {
                store.saveEvents(batch);
                store.mergeApiMetrics(batch, ApiMetricAggregator.aggregate(batch.events()));
                List<TelemetryEvent> errors = batch.events().stream()
                        .filter(e -> e.type() == EventType.ERROR)
                        .toList();
                store.saveErrorLogs(batch, errors);
                reception.recordReceived(batch.organizationId(), batch.serviceId(), batch.source(),
                        batch.receivedAt());
            });
            processed.incrementAndGet();
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            log.error("Failed to process telemetry batch {} ({} events) for service {}", batch.batchId(),
                    batch.events().size(), batch.serviceId(), e);
        } finally {
            TenantContext.clear();
        }
    }

    public Stats stats() {
        return new Stats(queue.size(), queue.remainingCapacity(), inFlight.get(), processed.get(), failed.get());
    }

    public record Stats(int queued, int remainingCapacity, int inFlight, long processed, long failed) {
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        AtomicInteger n = new AtomicInteger();
        pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "telemetry-worker-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < workers; i++) {
            pool.submit(this::workLoop);
        }
        log.info("Telemetry processor started: {} workers, queue capacity {}", workers,
                queue.remainingCapacity());
    }

    private void workLoop() {
        while (running.get() || !queue.isEmpty()) {
            try {
                IngestBatch batch = queue.poll(500, TimeUnit.MILLISECONDS);
                if (batch != null) {
                    inFlight.incrementAndGet();
                    try {
                        process(batch);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(20, TimeUnit.SECONDS)) {
                log.warn("Telemetry processor stopped with {} batches still queued (dropped)", queue.size());
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
