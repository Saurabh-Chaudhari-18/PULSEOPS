package com.pulseops.incident.service;

import java.util.Map;
import java.util.Set;

import com.pulseops.common.error.ApiException;

/**
 * Incident lifecycle: open → investigating / acknowledged → resolved → closed. A resolved
 * incident can be reopened; a closed one is final (open a new incident instead).
 */
public final class IncidentTransitions {

    static final Map<String, Set<String>> ALLOWED = Map.of(
            "open", Set.of("investigating", "acknowledged", "resolved"),
            "investigating", Set.of("acknowledged", "resolved"),
            "acknowledged", Set.of("investigating", "resolved"),
            "resolved", Set.of("open", "closed"),
            "closed", Set.of());

    private IncidentTransitions() {
    }

    public static void check(String from, String to) {
        if (!ALLOWED.containsKey(to)) {
            throw ApiException.badRequest("INVALID_STATUS",
                    "status must be open, investigating, acknowledged, resolved or closed");
        }
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.conflict("INVALID_TRANSITION", "An incident cannot go from " + from + " to " + to);
        }
    }

    public static boolean isActive(String status) {
        return !"resolved".equals(status) && !"closed".equals(status);
    }
}
