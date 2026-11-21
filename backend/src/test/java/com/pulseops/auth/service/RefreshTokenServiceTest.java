package com.pulseops.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.pulseops.audit.service.AuditService;
import com.pulseops.auth.domain.RefreshToken;
import com.pulseops.auth.repository.RefreshTokenRepository;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.security.SecureTokens;
import com.pulseops.testsupport.MutableClock;
import com.pulseops.testsupport.TestProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    RefreshTokenRepository repository;
    @Mock
    AuditService audit;

    private final MutableClock clock = MutableClock.startingAt("2026-03-01T10:00:00Z");
    private final UUID userId = UUID.randomUUID();
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(repository, audit, clock, TestProperties.defaults());
    }

    @Test
    void issuePersistsOnlyTheHash() {
        RefreshTokenService.IssuedRefreshToken issued = service.issue(userId, "JUnit");

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        Object storedHash = ReflectionTestUtils.getField(saved.getValue(), "tokenHash");
        assertThat(storedHash).isEqualTo(SecureTokens.sha256Hex(issued.rawToken()));
        assertThat(storedHash).isNotEqualTo(issued.rawToken());
        assertThat(issued.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(14)));
    }

    @Test
    void rotationRevokesPresentedTokenAndLinksSuccessor() {
        RefreshToken current = token(clock.instant().plus(Duration.ofDays(1)));
        when(repository.findByTokenHashForUpdate(SecureTokens.sha256Hex("raw"))).thenReturn(Optional.of(current));

        RefreshTokenService.Rotation rotation = service.rotate("raw", "JUnit");

        assertThat(rotation.userId()).isEqualTo(userId);
        assertThat(rotation.token().rawToken()).isNotEqualTo("raw");
        assertThat(current.isRevoked()).isTrue();
        assertThat(current.wasRotated()).isTrue();
        verify(repository, never()).revokeAllForUser(any(), any());
    }

    @Test
    void reusingARotatedTokenRevokesEverySessionOfTheUser() {
        RefreshToken current = token(clock.instant().plus(Duration.ofDays(1)));
        current.rotateTo(UUID.randomUUID(), clock.instant().minusSeconds(30));
        when(repository.findByTokenHashForUpdate(SecureTokens.sha256Hex("stolen"))).thenReturn(Optional.of(current));
        when(repository.revokeAllForUser(userId, clock.instant())).thenReturn(3);

        assertThatThrownBy(() -> service.rotate("stolen", "attacker"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("SESSION_REVOKED");

        verify(repository).revokeAllForUser(userId, clock.instant());
        verify(audit).record(eq(userId), eq("auth.refresh_token_reuse_detected"), eq("user"), eq(userId), anyMap());
    }

    @Test
    void loggedOutTokenIsInvalidButNotTreatedAsTheft() {
        RefreshToken current = token(clock.instant().plus(Duration.ofDays(1)));
        current.revoke(clock.instant());
        when(repository.findByTokenHashForUpdate(SecureTokens.sha256Hex("raw"))).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.rotate("raw", "JUnit"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("SESSION_INVALID");
        verify(repository, never()).revokeAllForUser(any(), any());
    }

    @Test
    void expiredTokenIsRejected() {
        RefreshToken current = token(clock.instant().minusSeconds(1));
        when(repository.findByTokenHashForUpdate(SecureTokens.sha256Hex("raw"))).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.rotate("raw", "JUnit"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("SESSION_INVALID");
        assertThat(current.wasRotated()).isFalse();
    }

    @Test
    void missingOrUnknownTokenIsRejected() {
        assertThatThrownBy(() -> service.rotate(null, "JUnit")).isInstanceOf(ApiException.class);
        when(repository.findByTokenHashForUpdate(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.rotate("unknown", "JUnit")).isInstanceOf(ApiException.class);
    }

    private RefreshToken token(Instant expiresAt) {
        Instant issued = expiresAt.minus(Duration.ofDays(14));
        return new RefreshToken(userId, SecureTokens.sha256Hex("raw"), issued, expiresAt, "JUnit");
    }
}
