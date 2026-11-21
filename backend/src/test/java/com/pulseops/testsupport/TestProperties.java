package com.pulseops.testsupport;

import java.time.Duration;
import java.util.List;

import com.pulseops.common.config.PulseOpsProperties;

/** Builds {@link PulseOpsProperties} for unit tests without a Spring context. */
public final class TestProperties {

    public static final String TEST_JWT_SECRET = "dGVzdC1vbmx5LWp3dC1zZWNyZXQtdGhhdC1pcy1sb25nLWVub3VnaA==";

    private TestProperties() {
    }

    public static PulseOpsProperties withEmailVerification(boolean required) {
        return new PulseOpsProperties(
                "https://app.pulseops.test",
                new PulseOpsProperties.Security(
                        new PulseOpsProperties.Jwt(TEST_JWT_SECRET, Duration.ofMinutes(15), Duration.ofDays(14)),
                        new PulseOpsProperties.Cookie(true),
                        List.of("https://app.pulseops.test")),
                new PulseOpsProperties.Auth(required, Duration.ofHours(24), Duration.ofMinutes(30)),
                new PulseOpsProperties.Mail(true, "no-reply@pulseops.test"),
                new PulseOpsProperties.Ml("http://localhost:8500", null),
                new PulseOpsProperties.Ingest(1_048_576, 500));
    }

    public static PulseOpsProperties defaults() {
        return withEmailVerification(true);
    }
}
