package com.pulseops.credential.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pulseops.common.persistence.BaseEntity;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * An API key that lets a customer application send telemetry. Scoped to a project and
 * optionally one service. Only the hash of the key is stored; the plaintext is returned
 * exactly once at creation or rotation.
 */
@Entity
@Table(name = "integration_credentials")
public class IntegrationCredential extends BaseEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "service_id", updatable = false)
    private UUID serviceId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "key_prefix", nullable = false, updatable = false)
    private String keyPrefix;

    @Column(name = "secret_hash", nullable = false, updatable = false)
    private String secretHash;

    @Convert(converter = CredentialStatus.JpaConverter.class)
    @Column(name = "status", nullable = false)
    private CredentialStatus status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "rotated_from_id", updatable = false)
    private UUID rotatedFromId;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IntegrationCredential() {
    }

    /** @param plaintextKey hashed here and never stored */
    public IntegrationCredential(UUID organizationId, UUID projectId, UUID serviceId, String name,
                                 String plaintextKey, Instant expiresAt, UUID rotatedFromId, UUID createdBy) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.serviceId = serviceId;
        this.name = name;
        this.keyPrefix = IntegrationKeys.displayPrefix(plaintextKey);
        this.secretHash = IntegrationKeys.hash(plaintextKey);
        this.status = CredentialStatus.ACTIVE;
        this.expiresAt = expiresAt;
        this.rotatedFromId = rotatedFromId;
        this.createdBy = createdBy;
    }

    /** ACTIVE credentials past their expiry report EXPIRED even before a cleanup job runs. */
    public CredentialStatus effectiveStatus(Instant now) {
        if (status == CredentialStatus.ACTIVE && expiresAt != null && !now.isBefore(expiresAt)) {
            return CredentialStatus.EXPIRED;
        }
        return status;
    }

    public boolean isUsable(Instant now) {
        return effectiveStatus(now) == CredentialStatus.ACTIVE;
    }

    public void revoke(Instant now) {
        if (status != CredentialStatus.REVOKED) {
            this.status = CredentialStatus.REVOKED;
            this.revokedAt = now;
        }
    }

    /** Shortens (never extends) the lifetime, e.g. to give a rotated-out key a grace period. */
    public void expireNoLaterThan(Instant deadline) {
        if (expiresAt == null || deadline.isBefore(expiresAt)) {
            this.expiresAt = deadline;
        }
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public String getName() {
        return name;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public CredentialStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public UUID getRotatedFromId() {
        return rotatedFromId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
