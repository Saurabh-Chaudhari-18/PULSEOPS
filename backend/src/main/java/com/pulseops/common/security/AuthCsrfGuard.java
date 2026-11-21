package com.pulseops.common.security;

import jakarta.servlet.http.HttpServletRequest;

import com.pulseops.common.error.ApiException;

/**
 * CSRF defence for the cookie-authenticated endpoints (refresh, logout). Requires a custom
 * header: browsers cannot attach it to a cross-site request without a CORS preflight,
 * which only allowed origins pass. Combined with the SameSite=Strict cookie.
 */
public final class AuthCsrfGuard {

    public static final String HEADER = "X-PulseOps-Client";
    public static final String EXPECTED_VALUE = "web";

    private AuthCsrfGuard() {
    }

    public static void require(HttpServletRequest request) {
        if (!EXPECTED_VALUE.equals(request.getHeader(HEADER))) {
            throw ApiException.forbidden("CSRF_CHECK_FAILED", "Missing required client header");
        }
    }
}
