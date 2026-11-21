package com.pulseops.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.pulseops.audit.service.AuditService;
import com.pulseops.auth.domain.RefreshToken;
import com.pulseops.auth.repository.RefreshTokenRepository;
import com.pulseops.common.config.PulseOpsProperties;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.security.SecureTokens;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh-token lifecycle (ADR-005): opaque 256-bit tokens, hashed at rest, rotated on
 * every use. Presenting an already-rotated token means the token was copied — every
 * session of that user is revoked.
 *
 * <p>Concurrent refreshes with the same token are serialized by a row lock; the second
 * one sees a rotated token and triggers reuse detection. Clients must therefore not fire
 * parallel refreshes (the web client serializes them).</p>
 */
@Service
public class RefreshTokenService {

    static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final AuditService audit;
    private final Clock clock;
    private final Duration ttl;

    public RefreshTokenService(RefreshTokenRepository repository, AuditService audit, Clock clock,
                               PulseOpsProperties props) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
        this.ttl = props.security().jwt().refreshTokenTtl();
    }

    @Transactional
    public IssuedRefreshToken issue(UUID userId, String userAgent) {
        return issueAt(userId, userAgent, clock.instant());
    }

    /** Validates and rotates {@code rawToken}. Revocations persist even when this throws. */
    @Transactional(noRollbackFor = ApiException.class)
    public Rotation rotate(String rawToken, String userAgent) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalidSession();
        }
        RefreshToken current = repository.findByTokenHashForUpdate(SecureTokens.sha256Hex(rawToken))
                .orElseThrow(RefreshTokenService::invalidSession);
        Instant now = clock.instant();

        if (current.isRevoked()) {
            if (current.wasRotated()) {
                int revoked = repository.revokeAllForUser(current.getUserId(), now);
                audit.record(current.getUserId(), "auth.refresh_token_reuse_detected", "user",
                        current.getUserId(), Map.of("revokedSessions", revoked));
                throw ApiException.unauthorized("SESSION_REVOKED",
                        "This session was revoked for your security. Please sign in again.");
            }
            throw invalidSession();
        }
        if (current.isExpired(now)) {
            throw invalidSession();
        }
        IssuedRefreshToken next = issueAt(current.getUserId(), userAgent, now);
        current.rotateTo(next.id(), now);
        return new Rotation(current.getUserId(), next);
    }

    /** Idempotent: unknown or already-revoked tokens are ignored. */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        repository.findByTokenHash(SecureTokens.sha256Hex(rawToken)).ifPresent(t -> t.revoke(clock.instant()));
    }

    @Transactional
    public int revokeAll(UUID userId) {
        return repository.revokeAllForUser(userId, clock.instant());
    }

    private IssuedRefreshToken issueAt(UUID userId, String userAgent, Instant now) {
        String raw = SecureTokens.newToken(TOKEN_BYTES);
        RefreshToken token = new RefreshToken(userId, SecureTokens.sha256Hex(raw), now, now.plus(ttl), userAgent);
        repository.save(token);
        return new IssuedRefreshToken(token.getId(), raw, token.getExpiresAt());
    }

    private static ApiException invalidSession() {
        return ApiException.unauthorized("SESSION_INVALID", "Session expired. Please sign in again.");
    }

    public record IssuedRefreshToken(UUID id, String rawToken, Instant expiresAt) {
    }

    public record Rotation(UUID userId, IssuedRefreshToken token) {
    }
}
