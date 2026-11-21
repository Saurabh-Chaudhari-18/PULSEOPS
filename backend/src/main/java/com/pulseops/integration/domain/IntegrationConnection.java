package com.pulseops.integration.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pulseops.common.persistence.BaseEntity;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** One row per service: chosen integration method and observed telemetry reception. */
@Entity
@Table(name = "integration_connections")
public class IntegrationConnection extends BaseEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Convert(converter = IntegrationType.JpaConverter.class)
    @Column(name = "integration_type", nullable = false)
    private IntegrationType integrationType;

    @Convert(converter = ConnectionStatus.JpaConverter.class)
    @Column(name = "status", nullable = false)
    private ConnectionStatus status;

    @Column(name = "first_telemetry_at")
    private Instant firstTelemetryAt;

    @Column(name = "last_telemetry_at")
    private Instant lastTelemetryAt;

    @Column(name = "last_error")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IntegrationConnection() {
    }

    public IntegrationConnection(UUID organizationId, UUID serviceId, IntegrationType type) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.serviceId = serviceId;
        this.integrationType = type;
        this.status = ConnectionStatus.WAITING_FOR_DATA;
    }

    /**
     * Selecting a (different) method. Existing reception history is kept; a service that
     * already delivers data stays CONNECTED, otherwise it waits for its first telemetry.
     */
    public void configure(IntegrationType type) {
        this.integrationType = type;
        if (status != ConnectionStatus.DISABLED && status != ConnectionStatus.CONNECTED) {
            this.status = ConnectionStatus.WAITING_FOR_DATA;
            this.lastError = null;
        }
    }

    public void disable() {
        this.status = ConnectionStatus.DISABLED;
    }

    /** Re-enabling returns to CONNECTED only if data arrived recently, else WAITING_FOR_DATA. */
    public void enable(Instant now, java.time.Duration freshness) {
        if (status != ConnectionStatus.DISABLED) {
            return;
        }
        boolean recent = lastTelemetryAt != null && lastTelemetryAt.isAfter(now.minus(freshness));
        this.status = recent ? ConnectionStatus.CONNECTED : ConnectionStatus.WAITING_FOR_DATA;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public IntegrationType getIntegrationType() {
        return integrationType;
    }

    public ConnectionStatus getStatus() {
        return status;
    }

    public Instant getFirstTelemetryAt() {
        return firstTelemetryAt;
    }

    public Instant getLastTelemetryAt() {
        return lastTelemetryAt;
    }

    public String getLastError() {
        return lastError;
    }
}
