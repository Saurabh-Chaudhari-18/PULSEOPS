package com.pulseops.common.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.pulseops.common.config.PulseOpsProperties;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues short-lived access tokens. Tokens carry identity only (subject = user ID);
 * organization membership and permissions are resolved per request from the database,
 * so role changes and removals take effect immediately rather than at token expiry.
 */
@Service
public class JwtService {

    public static final String ISSUER = "pulseops";

    private final JwtEncoder encoder;
    private final Duration ttl;
    private final Clock clock;

    public JwtService(JwtEncoder encoder, PulseOpsProperties props, Clock clock) {
        this.encoder = encoder;
        this.ttl = props.security().jwt().accessTokenTtl();
        this.clock = clock;
    }

    public AccessToken issue(UUID userId, String email) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("email", email)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, expiresAt);
    }

    public record AccessToken(String value, Instant expiresAt) {
    }
}
