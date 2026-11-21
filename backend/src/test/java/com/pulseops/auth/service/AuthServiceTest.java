package com.pulseops.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.pulseops.audit.service.AuditService;
import com.pulseops.auth.domain.ActionPurpose;
import com.pulseops.common.config.PulseOpsProperties;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.mail.MailDispatcher;
import com.pulseops.common.security.JwtService;
import com.pulseops.testsupport.MutableClock;
import com.pulseops.testsupport.TestProperties;
import com.pulseops.user.domain.User;
import com.pulseops.user.domain.UserStatus;
import com.pulseops.user.repository.UserRepository;
import com.pulseops.user.service.PasswordPolicy;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

    private static final String GOOD_PASSWORD = "correct horse battery staple";

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final ActionTokenService actionTokens = mock(ActionTokenService.class);
    private final JwtService jwt = mock(JwtService.class);
    private final AuditService audit = mock(AuditService.class);
    private final MailDispatcher mail = mock(MailDispatcher.class);
    private final MutableClock clock = MutableClock.startingAt("2026-03-01T10:00:00Z");

    private AuthService service(boolean verificationRequired) {
        PulseOpsProperties props = TestProperties.withEmailVerification(verificationRequired);
        return new AuthService(users, encoder, new PasswordPolicy(), refreshTokens, actionTokens, jwt, audit,
                mail, new AuthEmails(props), props, clock);
    }

    // ------------------------------------------------------------------ login

    @Test
    void unknownEmailStillPerformsAPasswordComparison() {
        AuthService service = service(true);
        when(users.findByNormalizedEmail("ghost@acme.test")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("Ghost@Acme.test ", "whatever-password", "ua"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("INVALID_CREDENTIALS");
        verify(encoder).matches(eq("whatever-password"), any());
        verifyNoInteractions(refreshTokens, jwt);
    }

    @Test
    void wrongPasswordGivesSameErrorAndIsAudited() {
        AuthService service = service(true);
        User alice = user(UserStatus.ACTIVE);
        when(users.findByNormalizedEmail("alice@acme.test")).thenReturn(Optional.of(alice));
        when(encoder.matches("wrong-password-x", "hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login("alice@acme.test", "wrong-password-x", "ua"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("INVALID_CREDENTIALS");
        verify(audit).record(eq(alice.getId()), eq("auth.login_failed"), eq("user"), eq(alice.getId()), anyMap());
        verifyNoInteractions(refreshTokens, jwt);
    }

    @Test
    void unverifiedAccountCannotSignInEvenWithCorrectPassword() {
        AuthService service = service(true);
        User pending = user(UserStatus.PENDING_VERIFICATION);
        when(users.findByNormalizedEmail("alice@acme.test")).thenReturn(Optional.of(pending));
        when(encoder.matches(GOOD_PASSWORD, "hash")).thenReturn(true);

        assertThatThrownBy(() -> service.login("alice@acme.test", GOOD_PASSWORD, "ua"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("EMAIL_NOT_VERIFIED");
        verifyNoInteractions(refreshTokens, jwt);
    }

    @Test
    void successfulLoginIssuesBothTokensAndRecordsLogin() {
        AuthService service = service(true);
        User alice = user(UserStatus.ACTIVE);
        when(users.findByNormalizedEmail("alice@acme.test")).thenReturn(Optional.of(alice));
        when(encoder.matches(GOOD_PASSWORD, "hash")).thenReturn(true);
        var refresh = new RefreshTokenService.IssuedRefreshToken(UUID.randomUUID(), "r", clock.instant());
        when(refreshTokens.issue(alice.getId(), "ua")).thenReturn(refresh);
        when(jwt.issue(alice.getId(), "alice@acme.test"))
                .thenReturn(new JwtService.AccessToken("a", clock.instant()));

        AuthService.LoginResult result = service.login("alice@acme.test", GOOD_PASSWORD, "ua");

        assertThat(result.accessToken().value()).isEqualTo("a");
        assertThat(result.refreshToken()).isEqualTo(refresh);
        assertThat(alice.getLastLoginAt()).isEqualTo(clock.instant());
        verify(audit).record(eq(alice.getId()), eq("auth.login"), eq("user"), eq(alice.getId()), anyMap());
    }

    // ------------------------------------------------------------------ registration

    @Test
    void newRegistrationIsPendingAndSendsVerificationEmail() {
        AuthService service = service(true);
        when(users.findByNormalizedEmail("new@acme.test")).thenReturn(Optional.empty());
        when(encoder.encode(GOOD_PASSWORD)).thenReturn("bcrypt-hash");
        when(actionTokens.issue(any(), eq(ActionPurpose.EMAIL_VERIFICATION), any())).thenReturn("tok123");

        AuthService.RegistrationOutcome outcome = service.register(" New@Acme.test", GOOD_PASSWORD, " New User ");

        assertThat(outcome).isEqualTo(AuthService.RegistrationOutcome.VERIFICATION_PENDING);
        verify(users).save(argThat(u -> u.getEmail().equals("new@acme.test")
                && u.getStatus() == UserStatus.PENDING_VERIFICATION
                && u.getPasswordHash().equals("bcrypt-hash")
                && u.getFullName().equals("New User")));
        verify(mail).dispatch(argThat(m -> m.to().equals("new@acme.test")
                && m.body().contains("https://app.pulseops.test/verify-email?token=tok123")));
    }

    @Test
    void registeringAnExistingEmailLooksIdenticalButCreatesNothing() {
        AuthService service = service(true);
        when(users.findByNormalizedEmail("alice@acme.test")).thenReturn(Optional.of(user(UserStatus.ACTIVE)));

        AuthService.RegistrationOutcome outcome = service.register("alice@acme.test", GOOD_PASSWORD, "Someone");

        assertThat(outcome).isEqualTo(AuthService.RegistrationOutcome.VERIFICATION_PENDING);
        verify(users, never()).save(any());
        verify(encoder).encode(GOOD_PASSWORD); // timing parity with the new-account path
        verify(mail).dispatch(argThat(m -> m.subject().contains("sign-up attempt")));
        verifyNoInteractions(actionTokens);
    }

    @Test
    void withoutVerificationDuplicateEmailIsAnExplicitConflict() {
        AuthService service = service(false);
        when(users.findByNormalizedEmail("alice@acme.test")).thenReturn(Optional.of(user(UserStatus.ACTIVE)));

        assertThatThrownBy(() -> service.register("alice@acme.test", GOOD_PASSWORD, "Someone"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("EMAIL_IN_USE");
    }

    @Test
    void weakPasswordIsRejectedBeforeAnyLookup() {
        AuthService service = service(true);

        assertThatThrownBy(() -> service.register("a@acme.test", "short", "Someone"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("WEAK_PASSWORD");
        verifyNoInteractions(users);
    }

    // ------------------------------------------------------------------ reset

    @Test
    void passwordResetChangesHashVerifiesEmailAndRevokesAllSessions() {
        AuthService service = service(true);
        User pending = user(UserStatus.PENDING_VERIFICATION);
        when(actionTokens.consume("reset-tok", ActionPurpose.PASSWORD_RESET)).thenReturn(pending.getId());
        when(users.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(encoder.encode(GOOD_PASSWORD)).thenReturn("new-hash");

        service.resetPassword("reset-tok", GOOD_PASSWORD);

        assertThat(pending.getPasswordHash()).isEqualTo("new-hash");
        assertThat(pending.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(pending.getEmailVerifiedAt()).isEqualTo(clock.instant());
        verify(refreshTokens).revokeAll(pending.getId());
    }

    @Test
    void forgotPasswordForUnknownEmailDoesNothingVisible() {
        AuthService service = service(true);
        when(users.findByNormalizedEmail("ghost@acme.test")).thenReturn(Optional.empty());

        service.forgotPassword("ghost@acme.test");

        verifyNoInteractions(actionTokens, mail);
    }

    private static User user(UserStatus status) {
        return new User("alice@acme.test", "hash", "Alice", status);
    }
}
