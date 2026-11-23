package com.pulseops.explore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseops.common.error.ApiException;
import com.pulseops.explore.service.ExploreViews.TraceDetail;
import com.pulseops.explore.service.LogExplorerService.Level;

import org.junit.jupiter.api.Test;

class ExplorerServicesTest {

    private final LogExplorerService logs = new LogExplorerService(null, new ObjectMapper());

    @Test
    void levelFiltersAcceptMinimumOrExactLevels() {
        assertThat(LogExplorerService.levels(null, null)).isEqualTo(EnumSet.allOf(Level.class));
        assertThat(LogExplorerService.levels("warn", null)).containsExactly(Level.WARN, Level.ERROR, Level.FATAL);
        assertThat(LogExplorerService.levels("WARNING", null)).contains(Level.WARN);
        assertThat(LogExplorerService.levels("ERROR", "info, debug")).as("exact levels win")
                .containsExactly(Level.DEBUG, Level.INFO);
        assertThatThrownBy(() -> LogExplorerService.levels("loud", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_LEVEL"));
    }

    @Test
    void traceIdsAreValidatedAndNormalised() {
        assertThat(LogExplorerService.traceId(" 4BF92F3577B34DA6A3CE929D0E0E4736 "))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(LogExplorerService.traceId(null)).isNull();
        assertThatThrownBy(() -> LogExplorerService.traceId("abc"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_TRACE_ID"));
        assertThatThrownBy(() -> LogExplorerService.traceId("' OR 1=1 --' OR 1=1 --' OR 1=1 --"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void traceDetailBuildsTheTreeWithOffsetsAndTotals() {
        UUID svc = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-11-23T10:00:00Z");
        List<TraceExplorerService.SpanRow> rows = List.of(
                new TraceExplorerService.SpanRow("b", "a", svc, "checkout", "SELECT orders", "CLIENT",
                        t0.plusMillis(5), 100, "UNSET", null, "{\"db.system\":\"postgresql\"}"),
                new TraceExplorerService.SpanRow("a", null, svc, "checkout", "GET /orders/{id}", "SERVER", t0, 120,
                        "ERROR", "boom", "{}"));

        TraceDetail d = TraceExplorerService.detail("t", rows, false, List.of(), logs::json);

        assertThat(d.durationMs()).isEqualTo(120.0);
        assertThat(d.errorSpans()).isEqualTo(1);
        assertThat(d.complete()).isTrue();
        assertThat(d.services()).containsExactly("checkout");
        assertThat(d.spans()).extracting(s -> s.spanId() + ":" + s.depth()).containsExactly("a:0", "b:1");
        assertThat(d.spans().get(1).offsetMs()).isEqualTo(5.0);
        assertThat(d.spans().get(1).attributes().get("db.system").asText()).isEqualTo("postgresql");
    }
}
