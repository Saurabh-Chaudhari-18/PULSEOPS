package com.pulseops.common.persistence;

/**
 * Builds LIKE/ILIKE patterns from user input with wildcards escaped, so a search for
 * "50%_off" matches literally. Use with {@code ILIKE ? ESCAPE '\'}.
 */
public final class LikePatterns {

    private LikePatterns() {
    }

    /** "%term%" with \, % and _ escaped; null for blank input (meaning: no filter). */
    public static String contains(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        String escaped = term.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
