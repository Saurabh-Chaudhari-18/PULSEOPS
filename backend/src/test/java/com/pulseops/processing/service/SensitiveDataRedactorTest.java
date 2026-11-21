package com.pulseops.processing.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SensitiveDataRedactorTest {

    @ParameterizedTest
    @ValueSource(strings = {"password", "db.password", "Authorization", "X-Api-Key", "api_key", "set-cookie",
            "session_id", "creditCardNumber", "card_number", "cvv", "auth", "user.ssn", "refreshToken",
            "client_secret"})
    void secretKeysAreDetected(String key) {
        assertThat(SensitiveDataRedactor.isSensitiveKey(key)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"user.id", "order_total", "http.route", "author", "region", "duration_ms"})
    void ordinaryKeysAreKept(String key) {
        assertThat(SensitiveDataRedactor.isSensitiveKey(key)).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Authorization: Bearer abcDEF123456789xyz | Authorization: Bearer [REDACTED]",
            "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJlMTIz end | token [REDACTED] end",
            "jdbc:postgresql://admin:hunter2@db:5432/x | jdbc:postgresql://[REDACTED]@db:5432/x",
            "Card 4111 1111 1111 1111 declined | Card [REDACTED] declined",
            "Order 1234567890123 failed | Order 1234567890123 failed",
            "login failed password=hunter2&user=bob | login failed password=[REDACTED]&user=bob",
            "plain message | plain message"
    })
    void freeTextIsScrubbed(String input, String expected) {
        assertThat(SensitiveDataRedactor.scrub(input)).isEqualTo(expected);
    }

    @Test
    void pulseOpsKeysAreScrubbed() {
        String key = "po_" + "A".repeat(43);
        assertThat(SensitiveDataRedactor.scrub("key=" + key)).isEqualTo("key=[REDACTED]");
    }

    @Test
    void luhnCheckDistinguishesCardsFromIds() {
        assertThat(SensitiveDataRedactor.luhnValid("4111111111111111")).isTrue();
        assertThat(SensitiveDataRedactor.luhnValid("4111111111111112")).isFalse();
    }
}
