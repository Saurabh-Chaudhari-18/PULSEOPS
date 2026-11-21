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

    public record Ml(@DefaultValue("http://localhost:8500") String baseUrl, String sharedSecret) {
    }

    public record Ingest(
            @DefaultValue("1048576") int maxPayloadBytes,
            @DefaultValue("500") int maxBatchEvents) {
    }
}
