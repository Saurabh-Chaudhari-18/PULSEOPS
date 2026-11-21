package com.pulseops.common.security;

import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.pulseops.common.config.PulseOpsProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * HMAC-SHA256 JWT signing/validation (ADR-005). Startup fails fast when the secret is
 * missing or too short — PulseOps never runs with a default or weak signing key.
 */
@Configuration
public class JwtConfig {

    static final int MIN_SECRET_BYTES = 32;

    @Bean
    SecretKey jwtSigningKey(PulseOpsProperties props) {
        return signingKeyFrom(props.security().jwt().secret());
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(JwtService.ISSUER));
        return decoder;
    }

    static SecretKey signingKeyFrom(String base64Secret) {
        if (base64Secret == null || base64Secret.isBlank()) {
            throw new IllegalStateException(
                    "PULSEOPS_JWT_SECRET is not set. Generate one with: openssl rand -base64 48");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64Secret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PULSEOPS_JWT_SECRET must be base64-encoded", e);
        }
        if (key.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "PULSEOPS_JWT_SECRET must decode to at least " + MIN_SECRET_BYTES + " bytes");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }
}
