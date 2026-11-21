package com.pulseops.processing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pulseops.processing.model.EventType;
import com.pulseops.processing.model.TelemetryEvent;

import org.junit.jupiter.api.Test;

class ApiMetricAggregatorTest {

    @Test
    void groupsByRouteMethodStatusClassAndMinute() {
        var aggregates = List.copyOf(ApiMetricAggregator.aggregate(List.of(
                perf("2026-11-21T10:00:05Z", "/orders/{id}", 200, 10),
                perf("2026-11-21T10:00:55Z", "/orders/{id}", 201, 30),
                perf("2026-11-21T10:00:30Z", "/orders/{id}", 503, 900),
                perf("2026-11-21T10:01:00Z", "/orders/{id}", 200, 20),
                custom("2026-11-21T10:00:10Z"))));

        assertThat(aggregates).hasSize(3);
        var ok = aggregates.get(0);
        assertThat(ok.key()).isEqualTo(new ApiMetricAggregator.Key("/orders/{id}", "GET", "2xx",
                Instant.parse("2026-11-21T10:00:00Z")));
        assertThat(ok.requests()).isEqualTo(2);
        assertThat(ok.errors()).isZero();
        assertThat(ok.sumMs()).isEqualTo(40);
        assertThat(ok.minMs()).isEqualTo(10);
        assertThat(ok.maxMs()).isEqualTo(30);
        assertThat(LatencyHistogram.of(ok.histogram()).total()).isEqualTo(2);

        var failed = aggregates.get(1);
        assertThat(failed.key().statusClass()).isEqualTo("5xx");
        assertThat(failed.errors()).isEqualTo(1);

        assertThat(aggregates.get(2).key().bucketStart()).isEqualTo(Instant.parse("2026-11-21T10:01:00Z"));
    }

    private static TelemetryEvent perf(String at, String route, int status, double ms) {
        return new TelemetryEvent(EventType.PERFORMANCE, "req", Instant.parse(at), Map.of(),
                new TelemetryEvent.Http("GET", route, status, ms), null, null, null, null);
    }

    private static TelemetryEvent custom(String at) {
        return new TelemetryEvent(EventType.CUSTOM, "signup", Instant.parse(at), Map.of(), null, null, null, null,
                null);
    }
}
