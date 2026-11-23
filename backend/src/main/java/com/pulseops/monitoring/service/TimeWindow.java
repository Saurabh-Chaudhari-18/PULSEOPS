package com.pulseops.monitoring.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.pulseops.common.error.ApiException;

/**
 * A query time range aligned to whole buckets. Telemetry is stored in one-minute buckets,
 * so {@code from} is floored and {@code to} ceiled to the step, and series are evenly
 * spaced, epoch-aligned points of {@code stepSeconds}.
 *
 * <p>Defaults: the last hour. Limits: at most 31 days back-to-back, {@code to} at most five
 * minutes in the future, and a step that yields no more than {@link #MAX_POINTS} points.</p>
 */
public record TimeWindow(Instant from, Instant to, int stepSeconds) {

    public static final int[] STEPS = {60, 300, 900, 3_600, 21_600, 86_400};
    public static final int TARGET_POINTS = 360;
    public static final int MAX_POINTS = 1_440;
    public static final Duration MAX_RANGE = Duration.ofDays(31);
    public static final Duration DEFAULT_RANGE = Duration.ofHours(1);

    public static TimeWindow parse(String fromText, String toText, Integer step, Clock clock) {
        Instant now = clock.instant();
        Instant to = toText == null || toText.isBlank() ? now : instant("to", toText);
        Instant from = fromText == null || fromText.isBlank() ? to.minus(DEFAULT_RANGE) : instant("from", fromText);
        if (!from.isBefore(to)) {
            throw invalid("'from' must be before 'to'");
        }
        if (to.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw invalid("'to' cannot be in the future");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw invalid("The time range cannot exceed 31 days");
        }
        int stepSeconds = step == null ? autoStep(from, to) : validStep(step, from, to);
        return new TimeWindow(floor(from, stepSeconds), ceil(to, stepSeconds), stepSeconds);
    }

    /** Smallest supported step giving at most {@link #TARGET_POINTS} points. */
    static int autoStep(Instant from, Instant to) {
        long seconds = Duration.between(from, to).getSeconds();
        for (int s : STEPS) {
            if (seconds / s <= TARGET_POINTS) {
                return s;
            }
        }
        return STEPS[STEPS.length - 1];
    }

    private static int validStep(int step, Instant from, Instant to) {
        boolean supported = false;
        for (int s : STEPS) {
            supported |= s == step;
        }
        if (!supported) {
            throw invalid("step must be one of 60, 300, 900, 3600, 21600, 86400 seconds");
        }
        if (Duration.between(from, to).getSeconds() / step > MAX_POINTS) {
            throw invalid("step too small for this range (more than " + MAX_POINTS + " points)");
        }
        return step;
    }

    /** Bucket start times of the series, oldest first. */
    public List<Instant> points() {
        List<Instant> points = new ArrayList<>();
        for (Instant t = from; t.isBefore(to); t = t.plusSeconds(stepSeconds)) {
            points.add(t);
        }
        return points;
    }

    /** Seconds actually elapsed in the window (the current minute is only partly over). */
    public double elapsedSeconds(Clock clock) {
        Instant end = to.isAfter(clock.instant()) ? clock.instant() : to;
        return Math.max(1, Duration.between(from, end).toMillis() / 1000.0);
    }

    static Instant floor(Instant t, int step) {
        return Instant.ofEpochSecond(Math.floorDiv(t.getEpochSecond(), step) * step);
    }

    static Instant ceil(Instant t, int step) {
        Instant floored = floor(t, step);
        return floored.equals(t) ? t : floored.plusSeconds(step);
    }

    private static Instant instant(String name, String text) {
        try {
            return Instant.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw invalid("'" + name + "' must be an ISO-8601 instant such as 2026-11-23T10:00:00Z");
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest("INVALID_TIME_RANGE", message);
    }
}
