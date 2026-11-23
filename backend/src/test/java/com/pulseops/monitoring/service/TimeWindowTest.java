package com.pulseops.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.pulseops.common.error.ApiException;

import org.junit.jupiter.api.Test;

class TimeWindowTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T10:30:20Z"), ZoneOffset.UTC);

    private static String errorCode(Runnable call) {
        try {
            call.run();
            return "none";
        } catch (ApiException e) {
            return e.code();
        }
    }

    @Test
    void defaultsToTheLastHourAlignedToMinutes() {
        TimeWindow w = TimeWindow.parse(null, null, null, CLOCK);
        assertThat((Object) w.from()).isEqualTo(Instant.parse("2026-11-23T09:30:00Z"));
        assertThat((Object) w.to()).isEqualTo(Instant.parse("2026-11-23T10:31:00Z"));
        assertThat((Object) w.stepSeconds()).isEqualTo(60);
        assertThat((Object) w.points().size()).isEqualTo(61);
        assertThat((Object) w.elapsedSeconds(CLOCK)).isEqualTo(3620.0);
    }

    @Test
    void stepIsChosenToKeepSeriesShort() {
        assertThat((Object) TimeWindow.parse("2026-11-22T10:00:00Z", "2026-11-23T10:00:00Z", null, CLOCK)
                .stepSeconds()).isEqualTo(300);
        assertThat((Object) TimeWindow.parse("2026-11-16T10:00:00Z", "2026-11-23T10:00:00Z", null, CLOCK)
                .stepSeconds()).isEqualTo(3_600);
        TimeWindow explicit = TimeWindow.parse("2026-11-23T09:07:00Z", "2026-11-23T10:00:00Z", 900, CLOCK);
        assertThat((Object) explicit.from()).isEqualTo(Instant.parse("2026-11-23T09:00:00Z"));
        assertThat((Object) explicit.points().size()).isEqualTo(4);
    }

    @Test
    void rejectsBadRanges() {
        assertThat((Object) errorCode(() -> TimeWindow.parse("2026-11-23T10:00:00Z", "2026-11-23T09:00:00Z", null,
                CLOCK))).isEqualTo("INVALID_TIME_RANGE");
        assertThat((Object) errorCode(() -> TimeWindow.parse("2026-09-01T00:00:00Z", null, null, CLOCK)))
                .isEqualTo("INVALID_TIME_RANGE");
        assertThat((Object) errorCode(() -> TimeWindow.parse(null, "2026-11-24T00:00:00Z", null, CLOCK)))
                .isEqualTo("INVALID_TIME_RANGE");
        assertThat((Object) errorCode(() -> TimeWindow.parse("yesterday", null, null, CLOCK)))
                .isEqualTo("INVALID_TIME_RANGE");
        assertThat((Object) errorCode(() -> TimeWindow.parse(null, null, 61, CLOCK)))
                .isEqualTo("INVALID_TIME_RANGE");
        assertThat((Object) errorCode(() -> TimeWindow.parse("2026-11-01T00:00:00Z", "2026-11-23T00:00:00Z", 60,
                CLOCK))).isEqualTo("INVALID_TIME_RANGE");
    }
}
