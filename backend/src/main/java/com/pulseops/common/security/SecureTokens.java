package com.pulseops.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Cryptographically secure opaque tokens and their storage hashes.
 *
 * <p>Raw tokens (refresh tokens, email-verification/reset tokens, and later integration
 * credentials) are returned to the client once; only the SHA-256 hash is persisted.
 * SHA-256 (not BCrypt) is correct here: tokens carry >= 256 bits of entropy, so
 * brute-force resistance comes from the token, and lookups must be by exact hash.</p>
 */
public final class SecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private SecureTokens() {
    }

    /** A URL-safe token with {@code bytes} bytes of entropy (minimum 32). */
    public static String newToken(int bytes) {
        if (bytes < 32) {
            throw new IllegalArgumentException("tokens need at least 32 bytes of entropy");
        }
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return URL_ENCODER.encodeToString(buf);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
