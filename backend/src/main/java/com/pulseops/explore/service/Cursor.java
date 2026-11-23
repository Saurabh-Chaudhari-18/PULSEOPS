package com.pulseops.explore.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

import com.pulseops.common.error.ApiException;

/**
 * Opaque keyset-pagination cursor: the (time, id) of the last row of a page, so the next
 * page continues strictly after it even while new rows arrive. Not a secret — it only
 * names a position; every query is still scoped by organization and row-level security.
 */
public record Cursor(Instant time, String id) {

    private static final int MAX_LENGTH = 200;

    public String encode() {
        String raw = time + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @return null for a missing cursor */
    public static Cursor decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        if (token.length() > MAX_LENGTH) {
            throw invalid();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token.trim()), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            if (bar <= 0 || bar == raw.length() - 1) {
                throw invalid();
            }
            return new Cursor(Instant.parse(raw.substring(0, bar)), raw.substring(bar + 1));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return ApiException.badRequest("INVALID_CURSOR", "The page cursor is not valid; start from the first page");
    }
}
