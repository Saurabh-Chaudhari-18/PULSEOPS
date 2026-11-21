package com.pulseops.ingest.service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseops.common.config.PulseOpsProperties;
import com.pulseops.common.error.ApiError;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.error.ValidationFailedException;
import com.pulseops.common.tenancy.TenantContext;
import com.pulseops.credential.service.CredentialAuthenticator;
import com.pulseops.credential.service.CredentialAuthenticator.AuthenticatedKey;
import com.pulseops.ingest.api.IngestRequests;
import com.pulseops.integration.domain.IntegrationType;
import com.pulseops.integration.service.TelemetryReceptionRecorder;
import com.pulseops.processing.model.IngestBatch;
import com.pulseops.processing.service.EventNormalizer;
import com.pulseops.processing.service.TelemetryProcessor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * REST ingestion pipeline: authenticate → rate-limit → parse → resolve target service under
 * the key's tenant → validate & redact → enqueue (202). Everything before the queue is
 * synchronous so clients get precise errors; storage happens asynchronously.
 */
@Service
public class IngestService {

    private final CredentialAuthenticator authenticator;
    private final IngestTargetResolver targets;
    private final IngestRateLimiter rateLimiter;
    private final TelemetryReceptionRecorder reception;
    private final TelemetryProcessor processor;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final EventNormalizer normalizer;

    public IngestService(CredentialAuthenticator authenticator, IngestTargetResolver targets,
                         IngestRateLimiter rateLimiter, TelemetryReceptionRecorder reception,
                         TelemetryProcessor processor, ObjectMapper objectMapper, Clock clock,
                         PulseOpsProperties props) {
        this.authenticator = authenticator;
        this.targets = targets;
        this.rateLimiter = rateLimiter;
        this.reception = reception;
        this.processor = processor;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.normalizer = new EventNormalizer(clock, props.ingest().maxBatchEvents());
    }

    public IngestRequests.Accepted accept(String rawKey, byte[] body) {
        AuthenticatedKey key = authenticator.authenticate(rawKey);
        rateLimiter.check(key.credentialId());
        IngestRequests.EventBatch request = parse(body);

        return TenantContext.callInOrganization(key.organizationId(), () -> {
            UUID serviceId = targets.resolve(key, request.service(), true);
            EventNormalizer.Result result = normalizer.normalize(request.events());
            if (!result.valid()) {
                reception.recordRejected(key.organizationId(), serviceId, IntegrationType.REST_API,
                        summarize(result.violations()));
                throw new ValidationFailedException("Telemetry batch rejected",
                        result.violations().stream()
                                .map(v -> new ApiError.FieldViolation(v.field(), v.message()))
                                .toList());
            }
            Instant now = clock.instant();
            IngestBatch batch = new IngestBatch(UUID.randomUUID(), key.organizationId(), key.projectId(), serviceId,
                    key.credentialId(), IntegrationType.REST_API, now, result.events());
            if (!processor.submit(batch)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INGEST_BUSY",
                        "Ingestion is temporarily at capacity; retry shortly");
            }
            authenticator.markUsed(key);
            return new IngestRequests.Accepted(batch.batchId().toString(), batch.events().size());
        });
    }

    private IngestRequests.EventBatch parse(byte[] body) {
        try {
            IngestRequests.EventBatch batch = objectMapper.readValue(body, IngestRequests.EventBatch.class);
            if (batch == null) {
                throw ApiException.badRequest("MALFORMED_JSON", "Body must be a JSON object");
            }
            return batch;
        } catch (IOException e) {
            throw ApiException.badRequest("MALFORMED_JSON", "Body is not valid JSON for an event batch");
        }
    }

    static String summarize(List<EventNormalizer.Violation> violations) {
        EventNormalizer.Violation first = violations.get(0);
        String summary = first.field() + ": " + first.message();
        return violations.size() == 1 ? summary : summary + " (+" + (violations.size() - 1) + " more)";
    }
}
