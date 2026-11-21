package com.pulseops.otlp.mapping;

import java.util.Locale;

/** Maps OTLP severity to the application_logs.level values. */
public final class LogLevels {

    private LogLevels() {
    }

    /**
     * OTLP SeverityNumber ranges: 1–4 TRACE, 5–8 DEBUG, 9–12 INFO, 13–16 WARN, 17–20 ERROR,
     * 21–24 FATAL. When unspecified (0), the severity text is used, defaulting to INFO.
     */
    public static String fromSeverity(int number, String text) {
        if (number >= 1 && number <= 4) {
            return "TRACE";
        }
        if (number >= 5 && number <= 8) {
            return "DEBUG";
        }
        if (number >= 9 && number <= 12) {
            return "INFO";
        }
        if (number >= 13 && number <= 16) {
            return "WARN";
        }
        if (number >= 17 && number <= 20) {
            return "ERROR";
        }
        if (number >= 21 && number <= 24) {
            return "FATAL";
        }
        if (text == null) {
            return "INFO";
        }
        return switch (text.trim().toUpperCase(Locale.ROOT)) {
            case "TRACE", "FINEST", "FINER" -> "TRACE";
            case "DEBUG", "FINE", "CONFIG" -> "DEBUG";
            case "WARN", "WARNING" -> "WARN";
            case "ERROR", "SEVERE", "ERR" -> "ERROR";
            case "FATAL", "CRITICAL", "PANIC" -> "FATAL";
            default -> "INFO";
        };
    }
}
