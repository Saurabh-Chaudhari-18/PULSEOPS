package com.pulseops.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fixed-window rate limiter keyed by an arbitrary string (IP, email, credential).
 *
 * <p>Single-node only: in a multi-instance SaaS deployment each node limits independently
 * (documented in KNOWN_ISSUES). Memory is bounded by evicting expired windows once the
 * key count passes {@code maxKeys}.</p>
 */
public final class FixedWindowRateLimiter {

    private record Window(long startMillis, int count) {
    }

    private final int limit;
    private final long windowMillis;
    private final int maxKeys;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int limit, Duration window, int maxKeys, Clock clock) {
        if (limit <= 0 || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("limit and window must be positive");
        }
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.maxKeys = maxKeys;
        this.clock = clock;
    }

    public FixedWindowRateLimiter(int limit, Duration window) {
        this(limit, window, 100_000, Clock.systemUTC());
    }

    /** Records one attempt for {@code key}; returns false once the window's limit is exceeded. */
    public boolean tryAcquire(String key) {
        long now = clock.millis();
        Window w = windows.compute(key, (k, cur) ->
                cur == null || now - cur.startMillis() >= windowMillis
                        ? new Window(now, 1)
                        : new Window(cur.startMillis(), cur.count() + 1));
        if (windows.size() > maxKeys) {
            evictExpired(now);
        }
        return w.count() <= limit;
    }

    int trackedKeys() {
        return windows.size();
    }

    private void evictExpired(long now) {
        windows.entrySet().removeIf(e -> now - e.getValue().startMillis() >= windowMillis);
    }
}
