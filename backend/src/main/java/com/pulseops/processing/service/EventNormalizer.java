package com.pulseops.processing.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.pulseops.ingest.api.IngestRequests;
import com.pulseops.processing.model.EventType;
import com.pulseops.processing.model.TelemetryEvent;

/**
 * Validates and normalizes REST events into {@link TelemetryEvent}s, applying redaction.
 * All-or-nothing: if any event is invalid the batch is rejected with indexed field errors
 * (e.g. {@code events[3].timestamp}) so clients can fix exactly what is wrong.
 *
 * <p>Identifiers (name, type, timestamp, IDs) are rejected when invalid; free text
 * (messages, stack traces) is truncated rather than rejected.</p>
 */
public class EventNormalizer {

    static final int MAX_NAME = 200;
    static final int MAX_ATTRIBUTES = 50;
    static final int MAX_ATTRIBUTE_KEY = 100;
    static final int MAX_ATTRIBUTE_STRING = 1_000;
    static final int MAX_MESSAGE = 4_000;
    static final int MAX_STACK_TRACE = 16_000;
    static final int MAX_UNIT = 32;
    static final double MAX_DURATION_MS = 3_600_000;
    static final Duration MAX_AGE = Duration.ofDays(7);
    static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SPAN_ID = Pattern.compile("^[0-9a-f]{16}$");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private final Clock clock;
    private final int maxBatch;

    public EventNormalizer(Clock clock, int maxBatch) {
        this.clock = clock;
        this.maxBatch = maxBatch;
    }

    public record Violation(String field, String message) {
    }

    public record Result(List<TelemetryEvent> events, List<Violation> violations) {

        public boolean valid() {
            return violations.isEmpty();
        }
    }

    public Result normalize(List<IngestRequests.Event> raw) {
        List<Violation> violations = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            violations.add(new Violation("events", "must contain at least one event"));
            return new Result(List.of(), violations);
        }
        if (raw.size() > maxBatch) {
            violations.add(new Violation("events", "at most " + maxBatch + " events per batch"));
            return new Result(List.of(), violations);
        }
        Instant now = clock.instant();
        List<TelemetryEvent> events = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            IngestRequests.Event e = raw.get(i);
            String p = "events[" + i + "].";
            if (e == null) {
                violations.add(new Violation("events[" + i + "]", "must not be null"));
                continue;
            }
            int before = violations.size();
            TelemetryEvent normalized = normalizeOne(e, p, now, violations);
            if (violations.size() == before) {
                events.add(normalized);
            }
        }
        return violations.isEmpty() ? new Result(events, List.of()) : new Result(List.of(), violations);
    }

    private TelemetryEvent normalizeOne(IngestRequests.Event e, String p, Instant now, List<Violation> v) {
        EventType type = EventType.parse(e.type()).orElse(null);
        if (type == null) {
            v.add(new Violation(p + "type", "must be one of custom, error, performance, metric, business"));
        }
        String name = e.name() == null ? null : e.name().trim();
        if (name == null || name.isEmpty()) {
            v.add(new Violation(p + "name", "is required"));
        } else if (name.length() > MAX_NAME) {
            v.add(new Violation(p + "name", "must be at most " + MAX_NAME + " characters"));
        } else if (CONTROL.matcher(name).find()) {
            v.add(new Violation(p + "name", "must not contain control characters"));
        }
        Instant occurredAt = parseTimestamp(e.timestamp(), p, now, v);
        Map<String, Object> attributes = normalizeAttributes(e.attributes(), p, v);
        String traceId = optionalId(e.traceId(), TRACE_ID, 32, p + "traceId", v);
        String spanId = optionalId(e.spanId(), SPAN_ID, 16, p + "spanId", v);

        TelemetryEvent.Http http = null;
        TelemetryEvent.Metric metric = null;
        TelemetryEvent.Failure failure = null;
        if (type == EventType.PERFORMANCE) {
            http = normalizeHttp(e, p, v);
            if (http == null && e.durationMs() != null) {
                attributes.put("duration_ms", e.durationMs());
            }
        } else if (type == EventType.METRIC) {
            if (e.value() == null || !Double.isFinite(e.value())) {
                v.add(new Violation(p + "value", "is required for metric events and must be a finite number"));
            } else {
                String unit = e.unit() == null ? null : truncate(e.unit().trim(), MAX_UNIT);
                metric = new TelemetryEvent.Metric(e.value(), unit);
            }
        } else if (type == EventType.ERROR) {
            failure = new TelemetryEvent.Failure(
                    e.errorType() == null ? null : truncate(e.errorType().trim(), MAX_NAME),
                    SensitiveDataRedactor.scrub(truncate(e.message(), MAX_MESSAGE)),
                    SensitiveDataRedactor.scrub(truncate(e.stackTrace(), MAX_STACK_TRACE)));
        }
        if (type == null || name == null || occurredAt == null) {
            return null;
        }
        return new TelemetryEvent(type, name, occurredAt, attributes, http, metric, failure, traceId, spanId);
    }

    /** HTTP details for performance events; null for non-HTTP measurements (no route/path). */
    private TelemetryEvent.Http normalizeHttp(IngestRequests.Event e, String p, List<Violation> v) {
        Double duration = e.durationMs();
        Integer status = e.statusCode();
        boolean invalid = false;
        if (duration == null || !Double.isFinite(duration) || duration < 0 || duration > MAX_DURATION_MS) {
            v.add(new Violation(p + "durationMs", "is required for performance events, between 0 and "
                    + (long) MAX_DURATION_MS));
            invalid = true;
        }
        if (status != null && (status < 100 || status > 599)) {
            v.add(new Violation(p + "statusCode", "must be a valid HTTP status (100-599)"));
            invalid = true;
        }
        if (invalid) {
            return null;
        }
        String route;
        if (e.route() != null && !e.route().isBlank()) {
            route = RouteTemplates.clean(e.route());
        } else if (e.path() != null && !e.path().isBlank()) {
            route = RouteTemplates.fromPath(e.path());
        } else {
            return null;
        }
        return new TelemetryEvent.Http(normalizeMethod(e.httpMethod()), route, status, duration);
    }

    static String normalizeMethod(String method) {
        if (method == null) {
            return "OTHER";
        }
        String m = method.trim().toUpperCase(Locale.ROOT);
        return METHODS.contains(m) ? m : "OTHER";
    }

    private Instant parseTimestamp(String raw, String p, Instant now, List<Violation> v) {
        if (raw == null || raw.isBlank()) {
            v.add(new Violation(p + "timestamp", "is required (ISO-8601, e.g. 2026-01-01T12:00:00Z)"));
            return null;
        }
        Instant ts;
        try {
            ts = OffsetDateTime.parse(raw.trim()).toInstant();
        } catch (DateTimeParseException ex) {
            v.add(new Violation(p + "timestamp", "must be ISO-8601 with an offset, e.g. 2026-01-01T12:00:00Z"));
            return null;
        }
        if (ts.isBefore(now.minus(MAX_AGE))) {
            v.add(new Violation(p + "timestamp", "is older than " + MAX_AGE.toDays() + " days"));
            return null;
        }
        if (ts.isAfter(now.plus(MAX_FUTURE_SKEW))) {
            v.add(new Violation(p + "timestamp", "is more than " + MAX_FUTURE_SKEW.toMinutes()
                    + " minutes in the future (check the sender's clock)"));
            return null;
        }
        return ts;
    }

    private Map<String, Object> normalizeAttributes(Map<String, Object> raw, String p, List<Violation> v) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        if (raw.size() > MAX_ATTRIBUTES) {
            v.add(new Violation(p + "attributes", "at most " + MAX_ATTRIBUTES + " attributes"));
            return out;
        }
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            Object value = entry.getValue();
            if (key.isEmpty() || key.length() > MAX_ATTRIBUTE_KEY || CONTROL.matcher(key).find()) {
                v.add(new Violation(p + "attributes", "keys must be 1-" + MAX_ATTRIBUTE_KEY
                        + " printable characters"));
                continue;
            }
            if (value == null) {
                continue;
            }
            if (SensitiveDataRedactor.isSensitiveKey(key)) {
                out.put(key, SensitiveDataRedactor.REDACTED);
            } else if (value instanceof String s) {
                out.put(key, SensitiveDataRedactor.scrub(truncate(s, MAX_ATTRIBUTE_STRING)));
            } else if (value instanceof Number n) {
                if (n instanceof Double d && !Double.isFinite(d)) {
                    v.add(new Violation(p + "attributes." + key, "must be a finite number"));
                } else {
                    out.put(key, n);
                }
            } else if (value instanceof Boolean b) {
                out.put(key, b);
            } else {
                v.add(new Violation(p + "attributes." + key, "must be a string, number or boolean"));
            }
        }
        return out;
    }

    private static String optionalId(String raw, Pattern pattern, int length, String field, List<Violation> v) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (!pattern.matcher(id).matches() || id.chars().allMatch(c -> c == '0')) {
            v.add(new Violation(field, "must be " + length + " lowercase hex characters (W3C trace context)"));
            return null;
        }
        return id;
    }

    static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
