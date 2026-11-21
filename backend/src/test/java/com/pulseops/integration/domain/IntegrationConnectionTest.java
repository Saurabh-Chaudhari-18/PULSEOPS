package com.pulseops.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class IntegrationConnectionTest {

    private final Instant now = Instant.parse("2026-03-01T10:00:00Z");
    private final Duration freshness = Duration.ofMinutes(15);

    @Test
    void newConnectionWaitsForDataInsteadOfClaimingConnected() {
        IntegrationConnection c = new IntegrationConnection(UUID.randomUUID(), UUID.randomUUID(), IntegrationType.REST_API);
        assertThat(c.getStatus()).isEqualTo(ConnectionStatus.WAITING_FOR_DATA);
    }

    @Test
    void reconfiguringAConnectedServiceKeepsItConnected() {
        IntegrationConnection c = connection(ConnectionStatus.CONNECTED, now);
        c.configure(IntegrationType.OTLP);
        assertThat(c.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(c.getIntegrationType()).isEqualTo(IntegrationType.OTLP);
    }

    @Test
    void reconfiguringAfterAnErrorClearsItAndWaits() {
        IntegrationConnection c = connection(ConnectionStatus.CONNECTION_ERROR, null);
        ReflectionTestUtils.setField(c, "lastError", "bad payload");
        c.configure(IntegrationType.REST_API);
        assertThat(c.getStatus()).isEqualTo(ConnectionStatus.WAITING_FOR_DATA);
        assertThat(c.getLastError()).isNull();
    }

    @Test
    void reconfiguringDoesNotSilentlyReEnable() {
        IntegrationConnection c = connection(ConnectionStatus.DISABLED, null);
        c.configure(IntegrationType.REST_API);
        assertThat(c.getStatus()).isEqualTo(ConnectionStatus.DISABLED);
    }

    @Test
    void enablingReturnsToConnectedOnlyWithRecentData() {
        IntegrationConnection recent = connection(ConnectionStatus.DISABLED, now.minus(Duration.ofMinutes(5)));
        recent.enable(now, freshness);
        assertThat(recent.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);

        IntegrationConnection stale = connection(ConnectionStatus.DISABLED, now.minus(Duration.ofHours(2)));
        stale.enable(now, freshness);
        assertThat(stale.getStatus()).isEqualTo(ConnectionStatus.WAITING_FOR_DATA);
    }

    private static IntegrationConnection connection(ConnectionStatus status, Instant lastTelemetry) {
        IntegrationConnection c = new IntegrationConnection(UUID.randomUUID(), UUID.randomUUID(), IntegrationType.REST_API);
        ReflectionTestUtils.setField(c, "status", status);
        ReflectionTestUtils.setField(c, "lastTelemetryAt", lastTelemetry);
        return c;
    }
}
