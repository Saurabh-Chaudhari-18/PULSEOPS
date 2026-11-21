package com.pulseops.auth.service;

import java.util.Map;
import java.util.UUID;

import com.pulseops.audit.service.AuditService;
import com.pulseops.user.service.UserService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authenticated security actions on the caller's own account. */
@Service
public class AccountSecurityService {

    private final UserService userService;
    private final RefreshTokenService refreshTokens;
    private final AuditService audit;

    public AccountSecurityService(UserService userService, RefreshTokenService refreshTokens, AuditService audit) {
        this.userService = userService;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
    }

    /** Changes the password and ends every session atomically (re-login required everywhere). */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        userService.changePassword(userId, currentPassword, newPassword);
        refreshTokens.revokeAll(userId);
    }

    /** "Sign out everywhere". Access tokens already issued expire within their short TTL. */
    @Transactional
    public void revokeAllSessions(UUID userId) {
        int revoked = refreshTokens.revokeAll(userId);
        audit.record(userId, "auth.sessions_revoked", "user", userId, Map.of("revokedSessions", revoked));
    }
}
