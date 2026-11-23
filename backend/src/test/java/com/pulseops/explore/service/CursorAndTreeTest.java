package com.pulseops.explore.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.pulseops.common.error.ApiException;

import org.junit.jupiter.api.Test;

class CursorAndTreeTest {

    record S(String id, String parent, int start) {
    }

    private static String code(Runnable call) {
        try {
            call.run();
            return "none";
        } catch (ApiException e) {
            return e.code();
        }
    }

    private static String render(List<TraceTree.Node<S>> nodes) {
        StringBuilder b = new StringBuilder();
        for (TraceTree.Node<S> n : nodes) {
            b.append(n.span().id()).append(':').append(n.depth()).append(n.orphan() ? "o" : "").append(' ');
        }
        return b.toString().trim();
    }

    @Test
    void cursorRoundTripsWithMicrosecondPrecision() {
        Cursor c = new Cursor(Instant.parse("2026-11-23T10:00:00.123456Z"), "00000000-0000-0000-0000-000000000003");
        Cursor back = Cursor.decode(c.encode());
        assertThat((Object) back).isEqualTo(c);
        assertThat((Object) Cursor.decode(null)).isNull();
        assertThat((Object) Cursor.decode(" ")).isNull();
    }

    @Test
    void tamperedCursorsAreRejected() {
        assertThat((Object) code(() -> Cursor.decode("not base64 !!"))).isEqualTo("INVALID_CURSOR");
        assertThat((Object) code(() -> Cursor.decode(java.util.Base64.getUrlEncoder().encodeToString(
                "yesterday|x".getBytes())))).isEqualTo("INVALID_CURSOR");
        assertThat((Object) code(() -> Cursor.decode(java.util.Base64.getUrlEncoder().encodeToString(
                "2026-11-23T10:00:00Z|".getBytes())))).isEqualTo("INVALID_CURSOR");
        assertThat((Object) code(() -> Cursor.decode("A".repeat(500)))).isEqualTo("INVALID_CURSOR");
    }

    @Test
    void spansAreOrderedDepthFirstByStartTime() {
        List<S> spans = List.of(
                new S("db2", "api", 30), new S("root", null, 0), new S("api", "root", 5), new S("db1", "api", 10),
                new S("cache", "root", 50));
        String tree = render(TraceTree.order(spans, S::id, S::parent, Comparator.comparingInt(S::start)));
        assertThat((Object) tree).isEqualTo("root:0 api:1 db1:2 db2:2 cache:1");
    }

    @Test
    void missingParentsBecomeOrphanRootsAndCyclesAreBroken() {
        List<S> spans = List.of(new S("child", "missing", 5), new S("root", null, 0),
                new S("a", "b", 20), new S("b", "a", 21), new S("self", "self", 30));
        String tree = render(TraceTree.order(spans, S::id, S::parent, Comparator.comparingInt(S::start)));
        assertThat((Object) tree).as("every span appears exactly once").isEqualTo("root:0 child:0o self:0o a:0o b:1");
    }
}
