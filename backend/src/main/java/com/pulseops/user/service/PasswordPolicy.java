package com.pulseops.user.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import com.pulseops.common.error.ApiException;

import org.springframework.stereotype.Component;

/**
 * Password rules (NIST SP 800-63B style): length over composition rules, a block-list
 * of the most common passwords, and no password equal to the account email.
 *
 * <p>Upper bound: BCrypt only uses the first 72 bytes, so longer inputs are rejected rather
 * than silently truncated.</p>
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_BYTES = 72;

    private static final Set<String> COMMON = Set.of(
            "password1234", "123456789012", "qwertyuiop12", "passwordpassword",
            "iloveyou1234", "welcome12345", "administrator", "letmein12345",
            "changeme1234", "password123!", "qwerty123456", "1234567890ab");

    public void validate(String password, String email) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw ApiException.badRequest("WEAK_PASSWORD",
                    "Password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw ApiException.badRequest("PASSWORD_TOO_LONG",
                    "Password must be at most " + MAX_BYTES + " bytes");
        }
        if (password.isBlank()) {
            throw ApiException.badRequest("WEAK_PASSWORD", "Password must not be blank");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower)) {
            throw ApiException.badRequest("WEAK_PASSWORD", "This password is too common");
        }
        if (email != null && lower.equals(email.toLowerCase(Locale.ROOT))) {
            throw ApiException.badRequest("WEAK_PASSWORD", "Password must not be your email address");
        }
        if (password.chars().distinct().count() < 4) {
            throw ApiException.badRequest("WEAK_PASSWORD", "Password is too repetitive");
        }
    }
}
