package com.pulseops.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pulseops.common.persistence.BaseEntity;

/** Single-use, expiring token for email verification or password reset (hash stored only). */
@Entity
@Table(name = "user_action_tokens")
public class UserActionToken extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Convert(converter = ActionPurpose.JpaConverter.class)
    @Column(name = "purpose", nullable = false, updatable = false)
    private ActionPurpose purpose;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserActionToken() {
    }

    public UserActionToken(UUID userId, ActionPurpose purpose, String tokenHash, Instant createdAt, Instant expiresAt) {
        super(UUID.randomUUID());
        this.userId = userId;
        this.purpose = purpose;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(ActionPurpose expected, Instant now) {
        return purpose == expected && consumedAt == null && now.isBefore(expiresAt);
    }

    public void consume(Instant now) {
        this.consumedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }
}
