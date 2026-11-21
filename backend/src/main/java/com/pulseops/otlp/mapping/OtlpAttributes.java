package com.pulseops.otlp.mapping;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.pulseops.processing.service.SensitiveDataRedactor;

/**
 * Filters OpenTelemetry attributes before storage: bounded count and size, secret-named
 * attributes redacted (e.g. {@code http.request.header.authorization}), free text scrubbed,
 * and SQL statement attributes reduced to their shape.
 */
public final class OtlpAttributes {

    public static final int MAX_ATTRIBUTES = 64;
    static final int MAX_KEY = 128;
    static final int MAX_STRING = 1_000;
    static final int MAX_STACK_TRACE = 16_000;
    private static final Set<String> SQL_KEYS = Set.of("db.statement", "db.query.text");
    /**
     * Dropped entirely: process command lines routinely carry secrets as plain flags
     * ("--password hunter2") that no pattern-based scrubber can reliably recognize.
     */
    private static final Set<String> DROPPED_KEYS = Set.of(
            "process.command_line", "process.command_args", "process.command");

    private OtlpAttributes() {
    }

    /** @param raw attribute values as plain Java types (String, Long, Double, Boolean, List) */
    public static Map<String, Object> sanitize(Map<String, Object> raw, int maxAttributes) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (out.size() >= maxAttributes) {
                break;
            }
            String key = e.getKey();
            Object value = e.getValue();
            if (key == null || key.isBlank() || key.length() > MAX_KEY || value == null
                    || DROPPED_KEYS.contains(key)) {
                continue;
            }
            if (SensitiveDataRedactor.isSensitiveKey(key)) {
                out.put(key, SensitiveDataRedactor.REDACTED);
            } else if (SQL_KEYS.contains(key) && value instanceof String sql) {
                out.put(key, SqlSanitizer.sanitize(sql));
            } else if ("exception.stacktrace".equals(key) && value instanceof String stack) {
                out.put(key, SensitiveDataRedactor.scrub(truncate(stack, MAX_STACK_TRACE)));
            } else if (value instanceof String s) {
                out.put(key, SensitiveDataRedactor.scrub(truncate(s, MAX_STRING)));
            } else if (value instanceof Number || value instanceof Boolean) {
                if (!(value instanceof Double d) || Double.isFinite(d)) {
                    out.put(key, value);
                }
            } else if (value instanceof Collection<?> list) {
                String joined = list.stream().map(String::valueOf).collect(Collectors.joining(","));
                out.put(key, SensitiveDataRedactor.scrub(truncate(joined, MAX_STRING)));
            } else {
                out.put(key, SensitiveDataRedactor.scrub(truncate(String.valueOf(value), MAX_STRING)));
            }
        }
        return out;
    }

    public static Map<String, Object> sanitize(Map<String, Object> raw) {
        return sanitize(raw, MAX_ATTRIBUTES);
    }

    /** First present attribute among {@code keys}, as a string. */
    public static String string(Map<String, Object> attrs, String... keys) {
        for (String k : keys) {
            Object v = attrs.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    /** First present attribute among {@code keys} that is (or parses as) an integer. */
    public static Long integer(Map<String, Object> attrs, String... keys) {
        for (String k : keys) {
            Object v = attrs.get(k);
            if (v instanceof Number n) {
                return n.longValue();
            }
            if (v instanceof String s) {
                try {
                    return Long.parseLong(s.trim());
                } catch (NumberFormatException ignored) {
                    // try next key
                }
            }
        }
        return null;
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
