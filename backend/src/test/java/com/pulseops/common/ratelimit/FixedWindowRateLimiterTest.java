package com.pulseops.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import com.pulseops.testsupport.MutableClock;

import org.junit.jupiter.api.Test;

class FixedWindowRateLimiterTest {

    private final MutableClock clock = MutableClock.startingAt("2026-01-01T00:00:00Z");

    @Test
    void allowsUpToLimitWithinWindowThenRejects() {
        var limiter = new FixedWindowRateLimiter(3, Duration.ofMinutes(1), 1000, clock);

        assertThat(limiter.tryAcquire("ip-1")).isTrue();
        assertThat(limiter.tryAcquire("ip-1")).isTrue();
        assertThat(limiter.tryAcquire("ip-1")).isTrue();
        assertThat(limiter.tryAcquire("ip-1")).isFalse();
    }

    @Test
    void keysAreIndependent() {
        var limiter = new FixedWindowRateLimiter(1, Duration.ofMinutes(1), 1000, clock);

        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("b")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
    }

    @Test
    void windowResetsAfterItElapses() {
        var limiter = new FixedWindowRateLimiter(1, Duration.ofMinutes(1), 1000, clock);
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();

        clock.advance(Duration.ofSeconds(60));

        assertThat(limiter.tryAcquire("a")).isTrue();
    }

    @Test
    void evictsExpiredWindowsWhenKeyBudgetExceeded() {
        var limiter = new FixedWindowRateLimiter(5, Duration.ofMinutes(1), 2, clock);
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        clock.advance(Duration.ofMinutes(2));

        limiter.tryAcquire("c"); // 3 keys > budget of 2 -> expired a, b evicted

        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void rejectsNonPositiveConfiguration() {
        assertThatThrownBy(() -> new FixedWindowRateLimiter(0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixedWindowRateLimiter(1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
