package com.pulseops.auth.api;

import java.time.Duration;

import com.pulseops.common.error.ApiException;
import com.pulseops.common.ratelimit.FixedWindowRateLimiter;

import org.springframework.stereotype.Component;

/**
 * Brute-force and abuse limits for the public auth endpoints.
 *
 * <p>Per-email login limits slow down guessing against one account from many IPs; the
 * trade-off is that an attacker can temporarily lock a known email out for one window
 * (documented in SECURITY.md). Per-email mail limits prevent mail-bombing a victim.</p>
 */
@Component
public class AuthRateLimits {

    private final FixedWindowRateLimiter loginPerIp = new FixedWindowRateLimiter(30, Duration.ofMinutes(5));
    private final FixedWindowRateLimiter loginPerEmail = new FixedWindowRateLimiter(10, Duration.ofMinutes(5));
    private final FixedWindowRateLimiter registerPerIp = new FixedWindowRateLimiter(10, Duration.ofHours(1));
    private final FixedWindowRateLimiter mailPerIp = new FixedWindowRateLimiter(10, Duration.ofHours(1));
    private final FixedWindowRateLimiter mailPerEmail = new FixedWindowRateLimiter(3, Duration.ofHours(1));
    private final FixedWindowRateLimiter tokenPerIp = new FixedWindowRateLimiter(20, Duration.ofMinutes(10));
    private final FixedWindowRateLimiter refreshPerIp = new FixedWindowRateLimiter(120, Duration.ofMinutes(5));

    void login(String ip, String normalizedEmail) {
        require(loginPerIp.tryAcquire(key(ip)) & loginPerEmail.tryAcquire(normalizedEmail));
    }

    void register(String ip) {
        require(registerPerIp.tryAcquire(key(ip)));
    }

    void emailSending(String ip, String normalizedEmail) {
        require(mailPerIp.tryAcquire(key(ip)) & mailPerEmail.tryAcquire(normalizedEmail));
    }

    void tokenSubmission(String ip) {
        require(tokenPerIp.tryAcquire(key(ip)));
    }

    void refresh(String ip) {
        require(refreshPerIp.tryAcquire(key(ip)));
    }

    private static String key(String ip) {
        return ip == null ? "unknown" : ip;
    }

    private static void require(boolean allowed) {
        if (!allowed) {
            throw ApiException.tooManyRequests("Too many attempts. Please wait and try again.");
        }
    }
}
