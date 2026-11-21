package com.pulseops.credential.domain;

import java.util.regex.Pattern;

import com.pulseops.common.security.SecureTokens;

/**
 * Integration key format: {@code po_} + 43 URL-safe characters (256 bits of entropy).
 * The recognizable prefix lets secret scanners (and humans) identify leaked keys; the
 * first 12 characters are stored as a non-secret display prefix, the full key only as a
 * SHA-256 hash.
 */
public final class IntegrationKeys {

    public static final String PREFIX = "po_";
    static final int SECRET_BYTES = 32;
    static final int KEY_LENGTH = PREFIX.length() + 43;
    static final int DISPLAY_PREFIX_LENGTH = 12;
    private static final Pattern FORMAT = Pattern.compile("^po_[A-Za-z0-9_-]{43}$");

    private IntegrationKeys() {
    }

    public static String generate() {
        return PREFIX + SecureTokens.newToken(SECRET_BYTES);
    }

    /** Cheap syntactic check so malformed keys are rejected without a database lookup. */
    public static boolean looksValid(String key) {
        return key != null && key.length() == KEY_LENGTH && FORMAT.matcher(key).matches();
    }

    public static String displayPrefix(String key) {
        return key.substring(0, DISPLAY_PREFIX_LENGTH);
    }

    public static String hash(String key) {
        return SecureTokens.sha256Hex(key);
    }
}
