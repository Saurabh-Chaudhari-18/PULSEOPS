package com.pulseops.otlp.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.google.protobuf.InvalidProtocolBufferException;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.tenancy.TenantContext;
import com.pulseops.credential.service.CredentialAuthenticator;
import com.pulseops.credential.service.CredentialAuthenticator.AuthenticatedKey;
import com.pulseops.ingest.service.IngestRateLimiter;
import com.pulseops.ingest.service.IngestTargetResolver;
import com.pulseops.integration.domain.IntegrationType;
import com.pulseops.otlp.decode.OtlpDecoder;
import com.pulseops.otlp.decode.OtlpDecoder.ResourceItems;
import com.pulseops.otlp.decode.OtlpPayload;
import com.pulseops.otlp.mapping.SpanAnalyzer;
import com.pulseops.processing.model.DbOperationRecord;
import com.pulseops.processing.model.IngestBatch;
import com.pulseops.processing.model.LogRecordData;
import com.pulseops.processing.model.MetricPointRecord;
import com.pulseops.processing.model.SpanRecord;
import com.pulseops.processing.model.TelemetryEvent;
import com.pulseops.processing.service.TelemetryProcessor;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsPartialSuccess;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsPartialSuccess;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.ExportTracePartialSuccess;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * OTLP/HTTP (binary protobuf) ingestion for traces, logs and metrics.
 *
 * <p>Pipeline: authenticate key → rate-limit → decompress (bounded) → parse → validate and
 * sanitize ({@link OtlpDecoder}) → attribute each resource to a service under the key's
 * tenant → one {@link IngestBatch} per service → enqueue. Like REST, everything before the
 * queue is synchronous; storage is asynchronous.</p>
 *
 * <p>Items that cannot be accepted individually (invalid IDs, out-of-window timestamps, a
 * resource naming an unknown service) are reported through OTLP's {@code partial_success}
 * so the exporter logs them instead of retrying. Problems that affect the whole request
 * (bad key, archived project, queue full) are HTTP errors.</p>
 */
@Service
public class OtlpIngestService {

    /** Cap on the decompressed body; the compressed body is separately capped by the controller. */
    public static final int MAX_DECOMPRESSED_BYTES = 16 * 1024 * 1024;

    private final CredentialAuthenticator authenticator;
    private final IngestRateLimiter rateLimiter;
    private final IngestTargetResolver targets;
    private final OtlpDecoder decoder;
    private final TelemetryProcessor processor;
    private final Clock clock;

    public OtlpIngestService(CredentialAuthenticator authenticator, IngestRateLimiter rateLimiter,
                             IngestTargetResolver targets, OtlpDecoder decoder, TelemetryProcessor processor,
                             Clock clock) {
        this.authenticator = authenticator;
        this.rateLimiter = rateLimiter;
        this.targets = targets;
        this.decoder = decoder;
        this.processor = processor;
        this.clock = clock;
    }

    /** Result of one export: items accepted, items rejected, and a summary of why. */
    public record Outcome(int accepted, long rejected, String message) {
    }

    // ----------------------------------------------------------------------------- per signal

    public ExportTraceServiceResponse traces(String rawKey, byte[] body, String contentEncoding) {
        AuthenticatedKey key = admit(rawKey);
        ExportTraceServiceRequest request;
        try {
            request = ExportTraceServiceRequest.parseFrom(OtlpPayload.decode(body, contentEncoding,
                    MAX_DECOMPRESSED_BYTES));
        } catch (InvalidProtocolBufferException e) {
            throw malformed();
        }
        OtlpDecoder.Decoded<SpanRecord> decoded = decoder.traces(request);
        Outcome outcome = accept(key, decoded.resources(), decoded.invalid(), "spans", this::traceBatch);
        ExportTraceServiceResponse.Builder response = ExportTraceServiceResponse.newBuilder();
        if (outcome.rejected() > 0) {
            response.setPartialSuccess(ExportTracePartialSuccess.newBuilder()
                    .setRejectedSpans(outcome.rejected())
                    .setErrorMessage(outcome.message()));
        }
        return response.build();
    }

    public ExportLogsServiceResponse logs(String rawKey, byte[] body, String contentEncoding) {
        AuthenticatedKey key = admit(rawKey);
        ExportLogsServiceRequest request;
        try {
            request = ExportLogsServiceRequest.parseFrom(OtlpPayload.decode(body, contentEncoding,
                    MAX_DECOMPRESSED_BYTES));
        } catch (InvalidProtocolBufferException e) {
            throw malformed();
        }
        OtlpDecoder.Decoded<LogRecordData> decoded = decoder.logs(request);
        Outcome outcome = accept(key, decoded.resources(), decoded.invalid(), "log records", this::logBatch);
        ExportLogsServiceResponse.Builder response = ExportLogsServiceResponse.newBuilder();
        if (outcome.rejected() > 0) {
            response.setPartialSuccess(ExportLogsPartialSuccess.newBuilder()
                    .setRejectedLogRecords(outcome.rejected())
                    .setErrorMessage(outcome.message()));
        }
        return response.build();
    }

    public ExportMetricsServiceResponse metrics(String rawKey, byte[] body, String contentEncoding) {
        AuthenticatedKey key = admit(rawKey);
        ExportMetricsServiceRequest request;
        try {
            request = ExportMetricsServiceRequest.parseFrom(OtlpPayload.decode(body, contentEncoding,
                    MAX_DECOMPRESSED_BYTES));
        } catch (InvalidProtocolBufferException e) {
            throw malformed();
        }
        OtlpDecoder.DecodedMetrics decoded = decoder.metrics(request);
        Outcome outcome = accept(key, decoded.resources(), decoded.invalidPoints(), "data points",
                this::metricBatch);
        ExportMetricsServiceResponse.Builder response = ExportMetricsServiceResponse.newBuilder();
        if (outcome.rejected() > 0) {
            response.setPartialSuccess(ExportMetricsPartialSuccess.newBuilder()
                    .setRejectedDataPoints(outcome.rejected())
                    .setErrorMessage(outcome.message()));
        }
        return response.build();
    }

    // --------------------------------------------------------------------- protocol-free core

    private AuthenticatedKey admit(String rawKey) {
        AuthenticatedKey key = authenticator.authenticate(rawKey);
        rateLimiter.check(key.credentialId());
        return key;
    }

    /** What a batch for one service is made of, before IDs and timestamps are assigned. */
    record Contents(List<TelemetryEvent> derivedRequests, List<SpanRecord> spans, List<LogRecordData> logs,
                    List<DbOperationRecord> dbOperations, List<MetricPointRecord> metricPoints) {
    }

    /**
     * Attributes decoded resources to services, groups them into one batch per service and
     * enqueues them. Package-private and protobuf-free so it can be unit-tested directly.
     *
     * @param invalid items the decoder already rejected
     * @param noun    plural item name used in the partial-success message
     */
    <T> Outcome accept(AuthenticatedKey key, List<ResourceItems<T>> resources, long invalid, String noun,
                       Function<List<T>, Contents> assemble) {
        return TenantContext.callInOrganization(key.organizationId(), () -> {
            Map<String, Object> resolved = new HashMap<>();   // service name -> UUID or ApiException
            Map<UUID, List<T>> byService = new LinkedHashMap<>();
            Map<UUID, IntegrationType> sourceByService = new HashMap<>();
            long unresolved = 0;
            ApiException firstFailure = null;

            for (ResourceItems<T> resource : resources) {
                String name = key.serviceId() != null ? null : resource.serviceName();
                Object target = resolved.computeIfAbsent(String.valueOf(name), n -> resolve(key, name));
                if (target instanceof ApiException failure) {
                    unresolved += resource.items().size();
                    if (firstFailure == null) {
                        firstFailure = failure;
                    }
                    continue;
                }
                UUID serviceId = (UUID) target;
                byService.computeIfAbsent(serviceId, id -> new ArrayList<>()).addAll(resource.items());
                sourceByService.putIfAbsent(serviceId, sourceOf(resource.resource()));
            }

            if (byService.isEmpty() && firstFailure != null) {
                // Nothing usable: a plain error is clearer than a 200 that rejects everything.
                throw firstFailure;
            }
            if (processor.remainingCapacity() < byService.size()) {
                throw busy();
            }

            Instant now = clock.instant();
            int accepted = 0;
            for (Map.Entry<UUID, List<T>> entry : byService.entrySet()) {
                Contents c = assemble.apply(entry.getValue());
                IngestBatch batch = new IngestBatch(UUID.randomUUID(), key.organizationId(), key.projectId(),
                        entry.getKey(), key.credentialId(), sourceByService.get(entry.getKey()), now, List.of(),
                        c.derivedRequests(), c.spans(), c.logs(), c.dbOperations(), c.metricPoints());
                if (!processor.submit(batch)) {
                    throw busy();
                }
                accepted += entry.getValue().size();
            }
            authenticator.markUsed(key);
            return new Outcome(accepted, invalid + unresolved, message(noun, invalid, unresolved, firstFailure));
        });
    }

    /** @return the service UUID, or the ApiException if this resource alone cannot be attributed */
    private Object resolve(AuthenticatedKey key, String serviceName) {
        try {
            return targets.resolve(key, serviceName, false);
        } catch (ApiException e) {
            boolean perResource = key.serviceId() == null && !e.code().startsWith("PROJECT_");
            if (!perResource) {
                throw e;    // affects every resource of this key: fail the request
            }
            return e;
        }
    }

    static String message(String noun, long invalid, long unresolved, ApiException failure) {
        List<String> parts = new ArrayList<>(2);
        if (invalid > 0) {
            parts.add(invalid + " " + noun + " had invalid IDs or timestamps outside the accepted window "
                    + "(7 days old to 5 minutes ahead)");
        }
        if (unresolved > 0) {
            parts.add(unresolved + " " + noun + " were not attributed to a service: " + failure.getMessage());
        }
        return String.join("; ", parts);
    }

    /**
     * JAVA_AGENT when the resource identifies the OpenTelemetry Java agent; any other SDK or
     * Collector is plain OTLP. Only affects how the connection is labelled.
     */
    static IntegrationType sourceOf(Map<String, Object> resource) {
        Object distro = resource.get("telemetry.distro.name");
        if (distro != null && String.valueOf(distro).toLowerCase(Locale.ROOT).contains("java-instrumentation")) {
            return IntegrationType.JAVA_AGENT;
        }
        Object language = resource.get("telemetry.sdk.language");
        if (resource.containsKey("telemetry.auto.version") && "java".equals(String.valueOf(language))) {
            return IntegrationType.JAVA_AGENT;
        }
        return IntegrationType.OTLP;
    }

    // ------------------------------------------------------------------------ batch assembly

    Contents traceBatch(List<SpanRecord> spans) {
        List<TelemetryEvent> requests = new ArrayList<>();
        List<LogRecordData> logs = new ArrayList<>();
        List<DbOperationRecord> dbOperations = new ArrayList<>();
        for (SpanRecord span : spans) {
            SpanAnalyzer.Derived derived = SpanAnalyzer.analyze(span);
            if (derived.request() != null) {
                requests.add(derived.request());
            }
            if (derived.dbOperation() != null) {
                dbOperations.add(derived.dbOperation());
            }
            logs.addAll(derived.exceptionLogs());
        }
        return new Contents(requests, spans, logs, dbOperations, List.of());
    }

    Contents logBatch(List<LogRecordData> logs) {
        return new Contents(List.of(), List.of(), logs, List.of(), List.of());
    }

    Contents metricBatch(List<MetricPointRecord> points) {
        return new Contents(List.of(), List.of(), List.of(), List.of(), points);
    }

    private static ApiException malformed() {
        return ApiException.badRequest("MALFORMED_PROTOBUF", "Body is not a valid OTLP protobuf export request");
    }

    private static ApiException busy() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INGEST_BUSY",
                "Ingestion is temporarily at capacity; retry shortly");
    }
}
