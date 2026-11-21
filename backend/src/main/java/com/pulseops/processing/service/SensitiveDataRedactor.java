package com.pulseops.processing.service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts secrets before anything is stored (SECURITY.md §Telemetry security).
 *
 * <ul>
 *   <li><b>Keys:</b> attributes whose name indicates a secret (password, token, authorization,
 *       cookie, API key, credential, card number, CVV, …) have their value replaced.</li>
 *   <li><b>Values:</b> free text (messages, stack traces, attribute values) is scrubbed of
 *       bearer tokens, JWTs, PulseOps keys, basic-auth credentials in URLs and payment card
 *       numbers (Luhn-checked to avoid masking ordinary IDs).</li>
 * </ul>
 * Over-redaction is preferred to leaking: a key like "tokenizer" is also masked.
 */
public final class SensitiveDataRedactor {

    public static final String REDACTED = "[REDACTED]";

    /** Substrings of the normalized (lowercase, alphanumeric-only) key that mark a secret. */
    private static final Set<String> SECRET_KEY_FRAGMENTS = Set.of(
            "password", "passwd", "passphrase", "secret", "token", "authorization", "cookie",
            "apikey", "accesskey", "privatekey", "credential", "cardnumber", "creditcard", "cvv", "cvc",
            "ssn", "sessionid", "dbpassword");
    /** Whole normalized keys that are secrets but too short to match as fragments. */
    private static final Set<String> SECRET_KEYS = Set.of("auth", "pwd", "pin", "pan", "jwt", "otp");

    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}");
    private static final Pattern PULSEOPS_KEY = Pattern.compile("\\bpo_[A-Za-z0-9_-]{43}");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/\\s:@]+:[^/\\s@]+@");
    private static final Pattern CARD_CANDIDATE = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key)\\s*[=:]\\s*[^\\s&,;\"']+");

    private SensitiveDataRedactor() {
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (SECRET_KEYS.contains(normalized)) {
            return true;
        }
        for (String fragment : SECRET_KEY_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** Scrubs secrets out of free text; returns the input unchanged when nothing matched. */
    public static String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = PULSEOPS_KEY.matcher(text).replaceAll(REDACTED);
        out = JWT.matcher(out).replaceAll(REDACTED);
        out = BEARER.matcher(out).replaceAll("$1 " + REDACTED);
        out = URL_CREDENTIALS.matcher(out).replaceAll("$1" + REDACTED + "@");
        out = KEY_VALUE_SECRET.matcher(out).replaceAll("$1=" + REDACTED);
        return maskCards(out);
    }

    private static String maskCards(String text) {
        Matcher m = CARD_CANDIDATE.matcher(text);
        StringBuilder sb = null;
        int last = 0;
        while (m.find()) {
            String digits = m.group().replaceAll("[ -]", "");
            if (digits.length() >= 13 && digits.length() <= 19 && luhnValid(digits)) {
                if (sb == null) {
                    sb = new StringBuilder(text.length());
                }
                sb.append(text, last, m.start()).append(REDACTED);
                last = m.end();
            }
        }
        if (sb == null) {
            return text;
        }
        return sb.append(text, last, text.length()).toString();
    }

    static boolean luhnValid(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
