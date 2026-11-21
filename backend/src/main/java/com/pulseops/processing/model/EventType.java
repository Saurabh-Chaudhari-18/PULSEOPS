package com.pulseops.processing.model;

import java.util.Locale;
import java.util.Optional;

/** Values match the telemetry_events.event_type CHECK constraint. */
public enum EventType {
    CUSTOM,
    ERROR,
    PERFORMANCE,
    METRIC,
    BUSINESS;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<EventType> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
