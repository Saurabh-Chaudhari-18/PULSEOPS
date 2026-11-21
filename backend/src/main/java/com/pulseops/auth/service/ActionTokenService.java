package com.pulseops.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.pulseops.auth.domain.ActionPurpose;
import com.pulseops.auth.domain.UserActionToken;
import com.pulseops.auth.repository.UserActionTokenRepository;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.security.SecureTokens;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issues and consumes single-use email-verification / password-reset tokens. */
@Service
public class ActionTokenService {

    static final int TOKEN_BYTES = 32;

    private final UserActionTokenRepository repository;
    private final Clock clock;

    public ActionTokenService(UserActionTokenRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Issues a fresh token and invalidates any outstanding token of the same purpose. */
    @Transactional
    public String issue(UUID userId, ActionPurpose purpose, Duration ttl) {
        Instant now = clock.instant();
        repository.invalidateOutstanding(userId, purpose, now);
        String raw = SecureTokens.newToken(TOKEN_BYTES);
        repository.save(new UserActionToken(userId, purpose, SecureTokens.sha256Hex(raw), now, now.plus(ttl)));
        return raw;
    }

    /** Consumes the token and returns its user; one generic error for every failure mode. */
    @Transactional
    public UUID consume(String rawToken, ActionPurpose purpose) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 200) {
            throw invalidToken();
        }
        Instant now = clock.instant();
        UserActionToken token = repository.findByTokenHashForUpdate(SecureTokens.sha256Hex(rawToken))
                .filter(t -> t.isUsable(purpose, now))
                .orElseThrow(ActionTokenService::invalidToken);
        token.consume(now);
        return token.getUserId();
    }

    private static ApiException invalidToken() {
        return ApiException.badRequest("INVALID_OR_EXPIRED_TOKEN", "This link is invalid or has expired");
    }
}
