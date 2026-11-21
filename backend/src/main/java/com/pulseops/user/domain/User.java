package com.pulseops.user.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pulseops.common.persistence.BaseEntity;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** A PulseOps account. Not tenant-scoped: one user can belong to many organizations. */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Convert(converter = UserStatus.JpaConverter.class)
    @Column(name = "status", nullable = false)
    private UserStatus status;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
    }

    /** @param email must already be normalized (see {@link #normalizeEmail}) */
    public User(String email, String passwordHash, String fullName, UserStatus status) {
        super(UUID.randomUUID());
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.status = status;
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public void markEmailVerified(Instant at) {
        this.emailVerifiedAt = at;
        if (status == UserStatus.PENDING_VERIFICATION) {
            this.status = UserStatus.ACTIVE;
        }
    }

    public void recordLogin(Instant at) {
        this.lastLoginAt = at;
    }

    public void changePasswordHash(String newHash) {
        this.passwordHash = newHash;
    }

    public void rename(String fullName) {
        this.fullName = fullName;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
