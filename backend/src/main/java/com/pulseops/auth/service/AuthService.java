package com.pulseops.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.pulseops.audit.service.AuditService;
import com.pulseops.auth.domain.ActionPurpose;
import com.pulseops.common.config.PulseOpsProperties;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.mail.MailDispatcher;
import com.pulseops.common.mail.OutboundMail;
import com.pulseops.common.persistence.AfterCommit;
import com.pulseops.common.security.JwtService;
import com.pulseops.common.security.JwtService.AccessToken;
import com.pulseops.common.security.SecureTokens;
import com.pulseops.user.domain.User;
import com.pulseops.user.domain.UserStatus;
import com.pulseops.user.repository.UserRepository;
import com.pulseops.user.service.PasswordPolicy;
import com.pulseops.user.service.UserView;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, email verification, login, token refresh, logout and password reset.
 *
 * <p>Account-enumeration defences: when verification is required, registration answers
 * identically for new and existing emails (existing owners get a notice email instead);
 * login performs a BCrypt comparison even for unknown emails so timing is uniform;
 * forgot-password and resend-verification always succeed. Emails are sent after commit
 * and off the request thread.</p>
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final RefreshTokenService refreshTokens;
    private final ActionTokenService actionTokens;
    private final JwtService jwtService;
    private final AuditService audit;
    private final MailDispatcher mail;
    private final AuthEmails emails;
    private final PulseOpsProperties props;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
                       RefreshTokenService refreshTokens, ActionTokenService actionTokens, JwtService jwtService,
                       AuditService audit, MailDispatcher mail, AuthEmails emails, PulseOpsProperties props,
                       Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.refreshTokens = refreshTokens;
        this.actionTokens = actionTokens;
        this.jwtService = jwtService;
        this.audit = audit;
        this.mail = mail;
        this.emails = emails;
        this.props = props;
        this.clock = clock;
        // Used to equalize login timing for unknown emails; the plaintext is discarded.
        this.dummyPasswordHash = passwordEncoder.encode(SecureTokens.newToken(32));
    }

    // ---------------------------------------------------------------- registration

    @Transactional
    public RegistrationOutcome register(String rawEmail, String password, String fullName) {
        String email = User.normalizeEmail(rawEmail);
        passwordPolicy.validate(password, email);
        boolean verificationRequired = props.auth().requireEmailVerification();

        Optional<User> existing = users.findByNormalizedEmail(email);
        if (existing.isPresent()) {
            if (!verificationRequired) {
                throw ApiException.conflict("EMAIL_IN_USE", "An account with this email already exists");
            }
            passwordEncoder.encode(password); // keep timing identical to the new-account path
            sendAfterCommit(emails.accountAlreadyExists(email));
            return RegistrationOutcome.VERIFICATION_PENDING;
        }

        User user = new User(email, passwordEncoder.encode(password), fullName.trim(),
                verificationRequired ? UserStatus.PENDING_VERIFICATION : UserStatus.ACTIVE);
        users.save(user);
        audit.record(user.getId(), "user.registered", "user", user.getId(),
                Map.of("verificationRequired", verificationRequired));

        if (!verificationRequired) {
            return RegistrationOutcome.ACTIVE;
        }
        String token = actionTokens.issue(user.getId(), ActionPurpose.EMAIL_VERIFICATION,
                props.auth().verificationTokenTtl());
        sendAfterCommit(emails.verification(email, user.getFullName(), token));
        return RegistrationOutcome.VERIFICATION_PENDING;
    }

    @Transactional
    public void verifyEmail(String token) {
        UUID userId = actionTokens.consume(token, ActionPurpose.EMAIL_VERIFICATION);
        User user = users.findById(userId).orElseThrow(() ->
                ApiException.badRequest("INVALID_OR_EXPIRED_TOKEN", "This link is invalid or has expired"));
        user.markEmailVerified(clock.instant());
        audit.record(userId, "user.email_verified", "user", userId, Map.of());
    }

    @Transactional
    public void resendVerification(String rawEmail) {
        users.findByNormalizedEmail(User.normalizeEmail(rawEmail))
                .filter(u -> u.getStatus() == UserStatus.PENDING_VERIFICATION)
                .ifPresent(user -> {
                    String token = actionTokens.issue(user.getId(), ActionPurpose.EMAIL_VERIFICATION,
                            props.auth().verificationTokenTtl());
                    sendAfterCommit(emails.verification(user.getEmail(), user.getFullName(), token));
                });
    }

    // ---------------------------------------------------------------- sessions

    /** Failed-attempt audit entries persist even though the call throws. */
    @Transactional(noRollbackFor = ApiException.class)
    public LoginResult login(String rawEmail, String password, String userAgent) {
        Optional<User> found = users.findByNormalizedEmail(User.normalizeEmail(rawEmail));
        if (found.isEmpty()) {
            passwordEncoder.matches(password == null ? "" : password, dummyPasswordHash);
            throw invalidCredentials();
        }
        User user = found.get();
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            audit.record(user.getId(), "auth.login_failed", "user", user.getId(), Map.of());
            throw invalidCredentials();
        }
        // Status is disclosed only after the password has been proven.
        if (user.getStatus() == UserStatus.DISABLED) {
            throw ApiException.forbidden("ACCOUNT_DISABLED", "This account has been disabled");
        }
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            throw ApiException.forbidden("EMAIL_NOT_VERIFIED", "Please verify your email address first");
        }
        user.recordLogin(clock.instant());
        RefreshTokenService.IssuedRefreshToken refresh = refreshTokens.issue(user.getId(), userAgent);
        AccessToken access = jwtService.issue(user.getId(), user.getEmail());
        audit.record(user.getId(), "auth.login", "user", user.getId(), Map.of());
        return new LoginResult(access, refresh, UserView.from(user));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public LoginResult refresh(String rawRefreshToken, String userAgent) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefreshToken, userAgent);
        User user = users.findById(rotation.userId()).filter(User::isActive).orElse(null);
        if (user == null) {
            refreshTokens.revokeAll(rotation.userId());
            throw ApiException.unauthorized("SESSION_INVALID", "Session expired. Please sign in again.");
        }
        AccessToken access = jwtService.issue(user.getId(), user.getEmail());
        return new LoginResult(access, rotation.token(), UserView.from(user));
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.revoke(rawRefreshToken);
    }

    // ---------------------------------------------------------------- password reset

    @Transactional
    public void forgotPassword(String rawEmail) {
        users.findByNormalizedEmail(User.normalizeEmail(rawEmail))
                .filter(u -> u.getStatus() != UserStatus.DISABLED)
                .ifPresent(user -> {
                    String token = actionTokens.issue(user.getId(), ActionPurpose.PASSWORD_RESET,
                            props.auth().resetTokenTtl());
                    audit.record(user.getId(), "auth.password_reset_requested", "user", user.getId(), Map.of());
                    sendAfterCommit(emails.passwordReset(user.getEmail(), user.getFullName(), token));
                });
    }

    /** A policy violation rolls back the token consumption, so the user can retry the same link. */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        UUID userId = actionTokens.consume(token, ActionPurpose.PASSWORD_RESET);
        User user = users.findById(userId).orElseThrow(() ->
                ApiException.badRequest("INVALID_OR_EXPIRED_TOKEN", "This link is invalid or has expired"));
        passwordPolicy.validate(newPassword, user.getEmail());
        Instant now = clock.instant();
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        user.markEmailVerified(now); // the reset link proved control of the mailbox
        int revoked = refreshTokens.revokeAll(userId);
        audit.record(userId, "auth.password_reset", "user", userId, Map.of("revokedSessions", revoked));
    }

    // ---------------------------------------------------------------- helpers

    private void sendAfterCommit(OutboundMail message) {
        AfterCommit.run(() -> mail.dispatch(message));
    }

    private static ApiException invalidCredentials() {
        return ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password");
    }

    public enum RegistrationOutcome {
        VERIFICATION_PENDING,
        ACTIVE
    }

    public record LoginResult(AccessToken accessToken, RefreshTokenService.IssuedRefreshToken refreshToken,
                              UserView user) {
    }
}
