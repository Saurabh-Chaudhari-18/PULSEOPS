package com.pulseops.processing.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns raw URL paths into low-cardinality route templates so that
 * {@code /orders/12345} and {@code /orders/67890} aggregate into {@code /orders/{id}}.
 *
 * <p>Applied only when the client did not send a route template itself (frameworks and the
 * OTel agent usually provide {@code http.route}). A segment becomes {@code {id}} when it is
 * numeric, a UUID, a long hex string, or a long token that mixes letters and digits.
 * Query strings and fragments are dropped; templates are capped in length and depth.</p>
 */
public final class RouteTemplates {

    static final int MAX_LENGTH = 300;
    static final int MAX_SEGMENTS = 12;
    private static final String ID = "{id}";

    private static final Pattern NUMERIC = Pattern.compile("^\\d+$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern LONG_HEX = Pattern.compile("^[0-9a-fA-F]{16,}$");
    private static final Pattern HAS_DIGIT = Pattern.compile(".*\\d.*");
    private static final Pattern HAS_LETTER = Pattern.compile(".*[A-Za-z].*");

    private RouteTemplates() {
    }

    public static String fromPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "/";
        }
        String path = rawPath.trim();
        int cut = indexOfAny(path, '?', '#');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        // Absolute URLs: keep only the path.
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            int slash = path.indexOf('/', scheme + 3);
            path = slash >= 0 ? path.substring(slash) : "/";
        }
        String[] parts = path.split("/");
        StringBuilder out = new StringBuilder();
        int segments = 0;
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (++segments > MAX_SEGMENTS) {
                out.append("/…");
                break;
            }
            out.append('/').append(isIdentifier(part) ? ID : part);
        }
        return clamp(out.length() == 0 ? "/" : out.toString());
    }

    /** Normalizes a client-provided template (trim, clamp, ensure leading slash). */
    public static String clean(String template) {
        String t = template.trim();
        if (!t.startsWith("/")) {
            t = "/" + t;
        }
        return clamp(t);
    }

    static boolean isIdentifier(String segment) {
        if (NUMERIC.matcher(segment).matches() || UUID.matcher(segment).matches()
                || LONG_HEX.matcher(segment).matches()) {
            return true;
        }
        // e.g. order-8f3k29x1, AB12CD34EF: long tokens mixing letters and digits.
        return segment.length() >= 8 && HAS_DIGIT.matcher(segment).matches()
                && HAS_LETTER.matcher(segment).matches()
                && !segment.toLowerCase(Locale.ROOT).matches("^v\\d+$");
    }

    private static String clamp(String s) {
        return s.length() <= MAX_LENGTH ? s : s.substring(0, MAX_LENGTH);
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        if (i < 0) {
            return j;
        }
        return j < 0 ? i : Math.min(i, j);
    }
}
