package com.pulseops.common.web;

import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Client network details for rate limiting and audit. The IP comes from
 * {@link HttpServletRequest#getRemoteAddr()}, which honours X-Forwarded-For only when
 * server.forward-headers-strategy is configured for a trusted proxy.
 */
public record ClientInfo(String ip, String userAgent) {

    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");
    private static final int MAX_USER_AGENT = 300;

    public static final ClientInfo UNKNOWN = new ClientInfo(null, null);

    public static ClientInfo from(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        String ua = request.getHeader("User-Agent");
        return new ClientInfo(
                ip != null && IP_LITERAL.matcher(ip).matches() ? ip : null,
                ua == null ? null : ua.substring(0, Math.min(ua.length(), MAX_USER_AGENT)));
    }

    /** Client info of the request bound to the current thread, or {@link #UNKNOWN}. */
    public static ClientInfo current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return from(attrs.getRequest());
        }
        return UNKNOWN;
    }
}
