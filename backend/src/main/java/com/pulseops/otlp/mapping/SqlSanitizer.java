package com.pulseops.otlp.mapping;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reduces SQL text to its shape so that parameters and literal values are never stored
 * (spec: "Do not collect SQL parameters or sensitive query contents by default").
 * The OpenTelemetry Java agent already sanitizes statements by default; this is a second,
 * independent layer for SDKs or agents configured otherwise.
 */
public final class SqlSanitizer {

    static final int MAX_LENGTH = 500;

    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern DOLLAR_QUOTED = Pattern.compile("\\$\\$.*?\\$\\$", Pattern.DOTALL);
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z_$0-9.])-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b");
    private static final Pattern HEX_LITERAL = Pattern.compile("\\b0[xX][0-9a-fA-F]+\\b");
    private static final Pattern IN_LIST = Pattern.compile("\\(\\s*\\?(?:\\s*,\\s*\\?)+\\s*\\)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SqlSanitizer() {
    }

    public static String sanitize(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        String s = DOLLAR_QUOTED.matcher(sql).replaceAll("?");
        s = STRING_LITERAL.matcher(s).replaceAll("?");
        s = HEX_LITERAL.matcher(s).replaceAll("?");
        s = NUMBER.matcher(s).replaceAll("?");
        s = IN_LIST.matcher(s).replaceAll("(?)");
        s = WHITESPACE.matcher(s).replaceAll(" ").trim();
        return s.length() <= MAX_LENGTH ? s : s.substring(0, MAX_LENGTH);
    }

    /** Maps an operation name or statement to the database_operations.operation_type CHECK values. */
    public static String operationType(String operationName, String statement) {
        String word = firstWord(operationName);
        String type = classify(word);
        if (!"OTHER".equals(type) || statement == null) {
            return type;
        }
        return classify(firstWord(statement));
    }

    private static String classify(String word) {
        if (word == null) {
            return "OTHER";
        }
        return switch (word) {
            case "SELECT", "WITH" -> "SELECT";
            case "INSERT", "UPSERT", "MERGE" -> "INSERT";
            case "UPDATE" -> "UPDATE";
            case "DELETE" -> "DELETE";
            case "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME" -> "DDL";
            case "CALL", "EXEC", "EXECUTE" -> "CALL";
            default -> "OTHER";
        };
    }

    private static String firstWord(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.stripLeading();
        int end = 0;
        while (end < trimmed.length() && Character.isLetter(trimmed.charAt(end))) {
            end++;
        }
        return end == 0 ? null : trimmed.substring(0, end).toUpperCase(Locale.ROOT);
    }
}
