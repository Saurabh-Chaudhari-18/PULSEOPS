package com.pulseops.ingest.service;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import com.pulseops.common.config.PulseOpsProperties;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.ratelimit.FixedWindowRateLimiter;

import org.springframework.stereotype.Component;

/** Per-integration-key request limit shared by every ingestion protocol (REST and OTLP). */
@Component
public class IngestRateLimiter {

    private final FixedWindowRateLimiter limiter;

    public IngestRateLimiter(PulseOpsProperties props, Clock clock) {
        this.limiter = new FixedWindowRateLimiter(props.ingest().batchesPerMinutePerKey(), Duration.ofMinutes(1),
                100_000, clock);
    }

    public void check(UUID credentialId) {
        if (!limiter.tryAcquire(credentialId.toString())) {
            throw ApiException.tooManyRequests("Rate limit for this integration key exceeded; slow down");
        }
    }
}
