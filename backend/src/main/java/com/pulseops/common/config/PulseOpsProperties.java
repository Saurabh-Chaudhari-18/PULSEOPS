package com.pulseops.common.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed view of every {@code pulseops.*} setting. Values come from environment
 * variables via application.yml; defaults here are the safe (production-like) choice.
 */
@ConfigurationProperties(prefix = "pulseops")
public record PulseOpsProperties(
        @DefaultValue("http://localhost:5173") String publicUrl,
        @DefaultValue Security security,
        @DefaultValue Auth auth,
        @DefaultValue Mail mail,
        @DefaultValue Ml ml,
        @DefaultValue Ingest ingest) {

    public record Security(
            @DefaultValue Jwt jwt,
            @DefaultValue Cookie cookie,
            @DefaultValue("http://localhost:5173") List<String> corsAllowedOrigins) {
    }

    public record Jwt(
            String secret,
            @DefaultValue("15m") Duration accessTokenTtl,
            @DefaultValue("14d") Duration refreshTokenTtl) {
    }

    public record Cookie(@DefaultValue("true") boolean secure) {
    }

    public record Auth(
            @DefaultValue("true") boolean requireEmailVerification,
            @DefaultValue("24h") Duration verificationTokenTtl,
            @DefaultValue("30m") Duration resetTokenTtl) {
    }

    /** devLogOnly defaults to false: without the dev profile, real SMTP is required. */
    public record Mail(
            @DefaultValue("false") boolean devLogOnly,
            @DefaultValue("no-reply@pulseops.local") String from) {
    }

    /**
     * ML service and the anomaly detection job (ADR-023/024).
     *
     * @param detectionEnabled  run the background detection job
     * @param detectionInterval time between runs
     * @param historyMinutes    baseline + evaluation window sent per series
     * @param evaluationMinutes newest minutes compared with the baseline
     * @param sensitivity       low | medium | high
     * @param resolveAfter      an active anomaly not seen again for this long is resolved
     */
    public record Ml(
            @DefaultValue("http://localhost:8500") String baseUrl,
            String sharedSecret,
            @DefaultValue("true") boolean detectionEnabled,
            @DefaultValue("60s") Duration detectionInterval,
            @DefaultValue("180") int historyMinutes,
            @DefaultValue("5") int evaluationMinutes,
            @DefaultValue("medium") String sensitivity,
            @DefaultValue("10m") Duration resolveAfter) {
    }

    /**
     * Ingestion settings.
     *
     * @param publicBaseUrl          URL customers' applications use to reach PulseOps ingestion
     *                               (shown in installation instructions)
     * @param maxPayloadBytes        request body limit
     * @param maxBatchEvents         events per REST batch
     * @param queueCapacity          batches buffered for asynchronous processing before 503
     * @param workerThreads          processing threads
     * @param batchesPerMinutePerKey per-credential rate limit
     */
    public record Ingest(
            @DefaultValue("http://localhost:8080") String publicBaseUrl,
            @DefaultValue("1048576") int maxPayloadBytes,
            @DefaultValue("500") int maxBatchEvents,
            @DefaultValue("200") int queueCapacity,
            @DefaultValue("2") int workerThreads,
            @DefaultValue("600") int batchesPerMinutePerKey) {
    }
}
