package com.pulseops.auth.api;

import java.time.Duration;
import java.time.Instant;

import com.pulseops.common.config.PulseOpsProperties;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh token travels only in an HttpOnly, SameSite=Strict cookie scoped to the auth
 * endpoints — never in a response body — so page scripts (and XSS) cannot read it.
 */
@Component
public class RefreshCookie {

    public static final String NAME = "pulseops_refresh";
    static final String PATH = "/api/v1/auth";

    private final boolean secure;

    public RefreshCookie(PulseOpsProperties props) {
        this.secure = props.security().cookie().secure();
    }

    ResponseCookie issue(String rawToken, Instant expiresAt) {
        Duration maxAge = Duration.between(Instant.now(), expiresAt);
        return base(rawToken).maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge).build();
    }

    ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH);
    }
}
