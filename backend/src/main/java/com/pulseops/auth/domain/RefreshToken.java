package com.pulseops.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pulseops.common.persistence.BaseEntity;

/**
 * A rotating refresh token. Only the SHA-256 hash of the opaque token is stored.
 * When rotated, {@code replacedById} links to its successor; presenting a token that was
 * already rotated is treated as theft (reuse detection) and revokes every session.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @Column(name = "user_agent", updatable = false)
    private String userAgent;

    protected RefreshToken() {
    }

    public RefreshToken(UUID userId, String tokenHash, Instant issuedAt, Instant expiresAt, String userAgent) {
        super(UUID.randomUUID());
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean wasRotated() {
        return replacedById != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public void rotateTo(UUID successorId, Instant now) {
        this.revokedAt = now;
        this.replacedById = successorId;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
