package com.pulseops.explore.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Orders the spans of one trace as a tree: depth-first, children by start time. Spans whose
 * parent was not received (another service not instrumented, or still in flight) become
 * additional roots instead of disappearing. Cycles in malformed input are broken.
 */
public final class TraceTree {

    private TraceTree() {
    }

    public record Node<T>(T span, int depth, boolean orphan) {
    }

    public static <T> List<Node<T>> order(List<T> spans, Function<T, String> id, Function<T, String> parent,
                                          Comparator<T> byStart) {
        Map<String, T> byId = new HashMap<>();
        for (T s : spans) {
            byId.putIfAbsent(id.apply(s), s);
        }
        Map<String, List<T>> children = new HashMap<>();
        List<T> roots = new ArrayList<>();
        for (T s : spans) {
            String p = parent.apply(s);
            if (p != null && byId.containsKey(p) && !p.equals(id.apply(s))) {
                children.computeIfAbsent(p, k -> new ArrayList<>()).add(s);
            } else {
                roots.add(s);
            }
        }
        roots.sort(byStart);
        children.values().forEach(list -> list.sort(byStart));

        List<Node<T>> out = new ArrayList<>(spans.size());
        Set<String> visited = new HashSet<>();
        for (T root : roots) {
            // A root with a parent ID (missing, or itself) is an orphan: its real parent was not received.
            walk(root, 0, parent.apply(root) != null, id, children, visited, out);
        }
        // Spans only reachable through a cycle were never visited: list them as roots.
        for (T s : spans) {
            if (!visited.contains(id.apply(s))) {
                walk(s, 0, true, id, children, visited, out);
            }
        }
        return out;
    }

    private static <T> void walk(T span, int depth, boolean orphan, Function<T, String> id,
                                 Map<String, List<T>> children, Set<String> visited, List<Node<T>> out) {
        if (!visited.add(id.apply(span))) {
            return;
        }
        out.add(new Node<>(span, depth, orphan));
        for (T child : children.getOrDefault(id.apply(span), List.of())) {
            walk(child, depth + 1, false, id, children, visited, out);
        }
    }
}
