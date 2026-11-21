package com.pulseops.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.pulseops.testsupport.MutableClock;
import com.pulseops.testsupport.TestProperties;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** Round-trips real tokens through the real Nimbus encoder/decoder wiring from JwtConfig. */
class JwtServiceTest {

    private final JwtConfig config = new JwtConfig();
    private final SecretKey key = JwtConfig.signingKeyFrom(TestProperties.TEST_JWT_SECRET);

    @Test
    void issuedTokenValidatesAndCarriesIdentity() {
        JwtService service = new JwtService(config.jwtEncoder(key), TestProperties.defaults(),
                java.time.Clock.systemUTC());
        UUID userId = UUID.randomUUID();

        JwtService.AccessToken token = service.issue(userId, "alice@acme.test");
        Jwt decoded = config.jwtDecoder(key).decode(token.value());

        assertThat(decoded.getSubject()).isEqualTo(userId.toString());
        assertThat(decoded.getClaimAsString("email")).isEqualTo("alice@acme.test");
        assertThat(decoded.getClaimAsString("iss")).isEqualTo(JwtService.ISSUER);
        assertThat(decoded.getExpiresAt()).isEqualTo(token.expiresAt());
    }

    @Test
    void expiredTokenIsRejected() {
        MutableClock clock = new MutableClock(Instant.now().minus(Duration.ofHours(2)));
        JwtService service = new JwtService(config.jwtEncoder(key), TestProperties.defaults(), clock);
        String token = service.issue(UUID.randomUUID(), "a@b.test").value();

        JwtDecoder decoder = config.jwtDecoder(key);
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        SecretKey otherKey = JwtConfig.signingKeyFrom("b3RoZXItc2VjcmV0LXRoYXQtaXMtZGVmaW5pdGVseS1sb25nLWVub3VnaA==");
        JwtService attacker = new JwtService(config.jwtEncoder(otherKey), TestProperties.defaults(),
                java.time.Clock.systemUTC());
        String forged = attacker.issue(UUID.randomUUID(), "evil@x.test").value();

        assertThatThrownBy(() -> config.jwtDecoder(key).decode(forged)).isInstanceOf(JwtException.class);
    }
}
