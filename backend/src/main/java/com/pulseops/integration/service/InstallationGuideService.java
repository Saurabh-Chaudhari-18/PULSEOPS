package com.pulseops.integration.service;

import java.util.List;
import java.util.UUID;

import com.pulseops.appservice.domain.MonitoredService;
import com.pulseops.appservice.service.ServiceCatalogService;
import com.pulseops.common.config.PulseOpsProperties;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.integration.domain.IntegrationType;

import org.springframework.stereotype.Service;

/**
 * Generates copy-paste installation instructions for a service. Keys are never embedded:
 * snippets reference the PULSEOPS_INGEST_KEY environment variable, because a key's
 * plaintext exists only in the response that created it.
 *
 * <p>Methods whose receiving endpoint is not implemented yet are returned with
 * {@code available=false} and a reason, instead of instructions that would silently fail.</p>
 */
@Service
public class InstallationGuideService {

    static final String KEY_HEADER = "X-PulseOps-Key";
    /** Phase 7 provides the OTLP receiver; until then Java-agent/OTLP guides are marked unavailable. */
    static final boolean OTLP_RECEIVER_AVAILABLE = false;

    private final ServiceCatalogService services;
    private final String baseUrl;

    public InstallationGuideService(ServiceCatalogService services, PulseOpsProperties props) {
        this.services = services;
        String url = props.ingest().publicBaseUrl();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public InstallationGuide guide(OrgAccess access, UUID serviceId, IntegrationType type) {
        MonitoredService service = services.requireLive(access.organizationId(), serviceId);
        return switch (type) {
            case REST_API -> restGuide(service);
            case JAVA_AGENT -> javaAgentGuide(service);
            case OTLP -> otlpGuide(service);
        };
    }

    private InstallationGuide restGuide(MonitoredService service) {
        String endpoint = baseUrl + "/api/v1/ingest/v1/events";
        String curl = """
                curl -X POST '%s' \\
                  -H 'Content-Type: application/json' \\
                  -H '%s: '"$PULSEOPS_INGEST_KEY" \\
                  -d '{
                    "service": "%s",
                    "events": [
                      {"type": "performance", "name": "GET /orders/{id}", "timestamp": "2026-01-01T12:00:00Z",
                       "httpMethod": "GET", "route": "/orders/{id}", "statusCode": 200, "durationMs": 42.5},
                      {"type": "error", "name": "PaymentDeclined", "timestamp": "2026-01-01T12:00:01Z",
                       "message": "Card issuer declined the payment", "errorType": "PaymentException"}
                    ]
                  }'
                """.formatted(endpoint, KEY_HEADER, jsonEscape(service.getName()));
        return new InstallationGuide(IntegrationType.REST_API, true, null, endpoint, List.of(
                new Step("Store the key", "Put the integration key in an environment variable or secret store "
                        + "(never in source control).", "export PULSEOPS_INGEST_KEY=po_..."),
                new Step("Send events", "POST batches of up to 500 events as JSON. The response is 202 Accepted; "
                        + "processing is asynchronous.", curl),
                new Step("Verify", "Return to the onboarding page. The status changes to CONNECTED as soon as the "
                        + "first batch is processed.", null)));
    }

    private InstallationGuide javaAgentGuide(MonitoredService service) {
        String endpoint = baseUrl + "/otlp";
        String jvm = """
                java -javaagent:/path/to/opentelemetry-javaagent.jar \\
                  -Dotel.service.name=%s \\
                  -Dotel.exporter.otlp.protocol=http/protobuf \\
                  -Dotel.exporter.otlp.endpoint=%s \\
                  -Dotel.exporter.otlp.headers=x-pulseops-key=${PULSEOPS_INGEST_KEY} \\
                  -Dotel.resource.attributes=deployment.environment=production \\
                  -jar your-app.jar
                """.formatted(shellQuote(service.getName()), endpoint);
        String env = """
                OTEL_SERVICE_NAME=%s
                OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
                OTEL_EXPORTER_OTLP_ENDPOINT=%s
                OTEL_EXPORTER_OTLP_HEADERS=x-pulseops-key=${PULSEOPS_INGEST_KEY}
                """.formatted(service.getName(), endpoint);
        return new InstallationGuide(IntegrationType.JAVA_AGENT, OTLP_RECEIVER_AVAILABLE, unavailableReason(),
                endpoint, List.of(
                new Step("Download the agent", "Download opentelemetry-javaagent.jar from the official "
                        + "OpenTelemetry Java instrumentation releases. No code changes are needed.", null),
                new Step("Attach the agent", "Add the agent and exporter settings to the JVM command line.", jvm),
                new Step("Or use environment variables", "Equivalent configuration for containers.", env),
                new Step("Privacy defaults", "The agent does not capture request/response headers or bodies by "
                        + "default, and sanitizes SQL statements. Keep those defaults.", null)));
    }

    private InstallationGuide otlpGuide(MonitoredService service) {
        String endpoint = baseUrl + "/otlp";
        String env = """
                OTEL_SERVICE_NAME=%s
                OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
                OTEL_EXPORTER_OTLP_ENDPOINT=%s
                OTEL_EXPORTER_OTLP_HEADERS=x-pulseops-key=${PULSEOPS_INGEST_KEY}
                """.formatted(service.getName(), endpoint);
        return new InstallationGuide(IntegrationType.OTLP, OTLP_RECEIVER_AVAILABLE, unavailableReason(), endpoint,
                List.of(new Step("Configure the OTLP exporter", "Works with any OpenTelemetry SDK (Python, Node.js, "
                        + ".NET, Go) using the standard environment variables.", env)));
    }

    private static String unavailableReason() {
        return OTLP_RECEIVER_AVAILABLE ? null
                : "The OTLP receiver is pending (implementation Phase 7). Use the REST API integration meanwhile.";
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "'\\''");
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public record InstallationGuide(IntegrationType type, boolean available, String unavailableReason,
                                    String endpoint, List<Step> steps) {
    }

    public record Step(String title, String description, String snippet) {
    }
}
