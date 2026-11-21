package com.pulseops.user.service;

import java.util.Map;
import java.util.UUID;

import com.pulseops.audit.service.AuditService;
import com.pulseops.common.error.ApiException;
import com.pulseops.user.domain.User;
import com.pulseops.user.repository.UserRepository;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Self-service profile management for the authenticated user. */
@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditService audit;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
                       AuditService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public UserView profile(UUID userId) {
        return UserView.from(load(userId));
    }

    @Transactional
    public UserView rename(UUID userId, String fullName) {
        User user = load(userId);
        user.rename(fullName.trim());
        audit.record(userId, "user.profile_updated", "user", userId, Map.of());
        return UserView.from(user);
    }

    /**
     * Changes the password after re-verifying the current one. The caller must revoke
     * the user's refresh tokens afterwards (sessions on other devices end).
     */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = load(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("INVALID_CURRENT_PASSWORD", "Current password is incorrect");
        }
        passwordPolicy.validate(newPassword, user.getEmail());
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("PASSWORD_UNCHANGED", "New password must differ from the current one");
        }
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        audit.record(userId, "user.password_changed", "user", userId, Map.of());
    }

    private User load(UUID userId) {
        return users.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Authentication required"));
    }
}
