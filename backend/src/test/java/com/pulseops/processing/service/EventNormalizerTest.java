package com.pulseops.processing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.pulseops.ingest.api.IngestRequests.Event;
import com.pulseops.processing.model.TelemetryEvent;

import org.junit.jupiter.api.Test;

class EventNormalizerTest {

    private static final String NOW = "2026-11-21T11:59:00Z";
    private final EventNormalizer normalizer = new EventNormalizer(
            Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC), 500);

    @Test
    void validBatchIsNormalizedAndRedacted() {
        Map<String, Object> attrs = Map.of("password", "hunter2", "user.id", 42, "note", "Bearer abcdefghijklmnop");
        var result = normalizer.normalize(List.of(
                event("performance", "GET /orders/{id}", NOW, attrs, "get", null, "/orders/987?x=1", 503, 120.5,
                        null, null, "4BF92F3577B34DA6A3CE929D0E0E4736"),
                event("ERROR", "PaymentDeclined", "2026-11-21T17:29:00+05:30", null, null, null, null, null, null,
                        null, "card 4111111111111111 declined", null),
                event("metric", "queue.depth", NOW, null, null, null, null, null, null, 17.0, null, null),
                event("performance", "batch-job", NOW, null, null, null, null, null, 900.0, null, null, null)));

        assertThat(result.valid()).isTrue();
        TelemetryEvent perf = result.events().get(0);
        assertThat(perf.http().route()).isEqualTo("/orders/{id}");
        assertThat(perf.http().method()).isEqualTo("GET");
        assertThat(perf.http().statusClass()).isEqualTo("5xx");
        assertThat(perf.http().isServerError()).isTrue();
        assertThat(perf.attributes()).containsEntry("password", "[REDACTED]")
                .containsEntry("note", "Bearer [REDACTED]")
                .containsEntry("user.id", 42);
        assertThat(perf.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");

        TelemetryEvent error = result.events().get(1);
        assertThat(error.occurredAt()).isEqualTo(Instant.parse("2026-11-21T11:59:00Z"));
        assertThat(error.error().message()).isEqualTo("card [REDACTED] declined");

        assertThat(result.events().get(2).metric().value()).isEqualTo(17.0);
        TelemetryEvent job = result.events().get(3);
        assertThat(job.http()).isNull();
        assertThat(job.attributes()).containsEntry("duration_ms", 900.0);
    }

    @Test
    void invalidBatchIsRejectedWholeWithIndexedErrors() {
        var result = normalizer.normalize(List.of(
                event("nonsense", "", NOW, null, null, null, null, null, null, null, null, null),
                event("performance", "x", "2026-11-10T00:00:00Z", null, "GET", "/a", null, 99, -1.0, null, null, "zz"),
                event("metric", "m", "yesterday", Map.of("nested", Map.of("a", 1)), null, null, null, null, null,
                        null, null, null),
                event("custom", "ok", "2026-11-21T12:30:00Z", null, null, null, null, null, null, null, null,
                        "00000000000000000000000000000000")));

        assertThat(result.valid()).isFalse();
        assertThat(result.events()).isEmpty();
        Set<String> fields = result.violations().stream().map(EventNormalizer.Violation::field)
                .collect(Collectors.toSet());
        assertThat(fields).contains("events[0].type", "events[0].name", "events[1].timestamp",
                "events[1].durationMs", "events[1].statusCode", "events[1].traceId", "events[2].timestamp",
                "events[2].value", "events[2].attributes.nested", "events[3].timestamp", "events[3].traceId");
    }

    @Test
    void batchAndAttributeLimitsAreEnforced() {
        assertThat(normalizer.normalize(List.of()).violations().get(0).field()).isEqualTo("events");

        List<Event> big = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            big.add(event("custom", "e", NOW, null, null, null, null, null, null, null, null, null));
        }
        assertThat(normalizer.normalize(big).violations().get(0).message()).contains("at most 500");

        Map<String, Object> many = new HashMap<>();
        for (int i = 0; i < 51; i++) {
            many.put("k" + i, i);
        }
        assertThat(normalizer.normalize(List.of(event("custom", "e", NOW, many, null, null, null, null, null, null,
                null, null))).violations().get(0).field()).isEqualTo("events[0].attributes");
    }

    @Test
    void unknownMethodsBecomeOther() {
        assertThat(EventNormalizer.normalizeMethod("brew")).isEqualTo("OTHER");
        assertThat(EventNormalizer.normalizeMethod(null)).isEqualTo("OTHER");
        assertThat(EventNormalizer.normalizeMethod(" patch ")).isEqualTo("PATCH");
    }

    private static Event event(String type, String name, String ts, Map<String, Object> attrs, String method,
                               String route, String path, Integer status, Double duration, Double value,
                               String message, String traceId) {
        return new Event(type, name, ts, attrs, method, route, path, status, duration, value, "ms", message,
                "PaymentException", null, traceId, null);
    }
}
