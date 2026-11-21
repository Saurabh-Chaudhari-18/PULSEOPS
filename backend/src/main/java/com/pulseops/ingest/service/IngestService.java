package com.pulseops.ingest.service;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseops.appservice.domain.MonitoredService;
import com.pulseops.appservice.repository.MonitoredServiceRepository;
import com.pulseops.common.config.PulseOpsProperties;
import com.pulseops.common.error.ApiError;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.error.ValidationFailedException;
import com.pulseops.common.ratelimit.FixedWindowRateLimiter;
import com.pulseops.common.tenancy.TenantContext;
import com.pulseops.credential.service.CredentialAuthenticator;
import com.pulseops.credential.service.CredentialAuthenticator.AuthenticatedKey;
import com.pulseops.ingest.api.IngestRequests;
import com.pulseops.integration.domain.ConnectionStatus;
import com.pulseops.integration.domain.IntegrationType;
import com.pulseops.integration.repository.IntegrationConnectionRepository;
import com.pulseops.integration.service.TelemetryReceptionRecorder;
import com.pulseops.processing.model.IngestBatch;
import com.pulseops.processing.service.EventNormalizer;
import com.pulseops.processing.service.TelemetryProcessor;
import com.pulseops.project.domain.Project;
import com.pulseops.project.repository.ProjectRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * REST ingestion pipeline: authenticate → rate-limit → parse → resolve target service under
 * the key's tenant → validate & redact → enqueue (202). Everything before the queue is
 * synchronous so clients get precise errors; storage happens asynchronously.
 */
@Service
public class IngestService {

    private final CredentialAuthenticator authenticator;
    private final ProjectRepository projects;
    private final MonitoredServiceRepository services;
    private final IntegrationConnectionRepository connections;
    private final TelemetryReceptionRecorder reception;
    private final TelemetryProcessor processor;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final EventNormalizer normalizer;
    private final FixedWindowRateLimiter perKeyLimiter;

    public IngestService(CredentialAuthenticator authenticator, ProjectRepository projects,
                         MonitoredServiceRepository services, IntegrationConnectionRepository connections,
                         TelemetryReceptionRecorder reception, TelemetryProcessor processor,
                         ObjectMapper objectMapper, TransactionTemplate tx, Clock clock, PulseOpsProperties props) {
        this.authenticator = authenticator;
        this.projects = projects;
        this.services = services;
        this.connections = connections;
        this.reception = reception;
        this.processor = processor;
        this.objectMapper = objectMapper;
        this.tx = tx;
        this.clock = clock;
        this.normalizer = new EventNormalizer(clock, props.ingest().maxBatchEvents());
        this.perKeyLimiter = new FixedWindowRateLimiter(props.ingest().batchesPerMinutePerKey(),
                Duration.ofMinutes(1), 100_000, clock);
    }

    public IngestRequests.Accepted accept(String rawKey, byte[] body) {
        AuthenticatedKey key = authenticator.authenticate(rawKey);
        if (!perKeyLimiter.tryAcquire(key.credentialId().toString())) {
            throw ApiException.tooManyRequests("Rate limit for this integration key exceeded; slow down");
        }
        IngestRequests.EventBatch request = parse(body);

        return TenantContext.callInOrganization(key.organizationId(), () -> {
            UUID serviceId = resolveService(key, request.service());
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

    /**
     * Must be called with the key's organization bound. Service-scoped keys may only send to
     * their own service; project-wide keys must name an existing service of the project.
     * Services are never auto-created from telemetry.
     */
    UUID resolveService(AuthenticatedKey key, String requestedName) {
        return tx.execute(status -> {
            Project project = projects.findLive(key.organizationId(), key.projectId())
                    .orElseThrow(() -> forbidden("PROJECT_UNAVAILABLE", "The key's project no longer exists"));
            if (!project.isActive()) {
                throw forbidden("PROJECT_ARCHIVED", "The key's project is archived");
            }
            MonitoredService service;
            if (key.serviceId() != null) {
                service = services.findLive(key.organizationId(), key.serviceId())
                        .orElseThrow(() -> forbidden("SERVICE_UNAVAILABLE", "The key's service no longer exists"));
                if (requestedName != null && !requestedName.isBlank()
                        && !service.getName().equalsIgnoreCase(requestedName.trim())) {
                    throw forbidden("SERVICE_SCOPE_MISMATCH",
                            "This key is scoped to service '" + service.getName() + "'");
                }
            } else {
                if (requestedName == null || requestedName.isBlank()) {
                    throw ApiException.badRequest("SERVICE_REQUIRED",
                            "Project-wide keys must name the target service in the 'service' field");
                }
                service = services.findLiveByName(key.organizationId(), key.projectId(), requestedName.trim())
                        .orElseThrow(() -> ApiException.notFound("UNKNOWN_SERVICE",
                                "No service named '" + requestedName.trim() + "' in this project; create it first"));
            }
            if (!service.isActive()) {
                throw forbidden("SERVICE_ARCHIVED", "Service '" + service.getName() + "' is archived");
            }
            boolean disabled = connections.findForService(key.organizationId(), service.getId())
                    .map(c -> c.getStatus() == ConnectionStatus.DISABLED)
                    .orElse(false);
            if (disabled) {
                throw forbidden("INGESTION_DISABLED", "Ingestion is disabled for service '" + service.getName() + "'");
            }
            return service.getId();
        });
    }

    static String summarize(List<EventNormalizer.Violation> violations) {
        EventNormalizer.Violation first = violations.get(0);
        String summary = first.field() + ": " + first.message();
        return violations.size() == 1 ? summary : summary + " (+" + (violations.size() - 1) + " more)";
    }

    private static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }
}
