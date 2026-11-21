package com.pulseops.user.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseops.common.error.ApiException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsLongUnusualPassword() {
        assertThatCode(() -> policy.validate("correct horse battery staple", "a@b.test")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "elevenchars"})
    void rejectsShortPasswords(String password) {
        assertThatThrownBy(() -> policy.validate(password, "a@b.test"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at least 12");
    }

    @Test
    void rejectsPasswordsBeyondBcryptLimitInsteadOfTruncating() {
        String seventyThreeBytes = "x".repeat(70) + "abc";
        assertThatThrownBy(() -> policy.validate(seventyThreeBytes, "a@b.test"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("PASSWORD_TOO_LONG");
    }

    @Test
    void countsBytesNotCharsForTheBcryptLimit() {
        String multiByte = "é".repeat(37); // 37 chars, 74 UTF-8 bytes
        assertThatThrownBy(() -> policy.validate(multiByte, "a@b.test"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("PASSWORD_TOO_LONG");
    }

    @Test
    void rejectsCommonPasswordsCaseInsensitively() {
        assertThatThrownBy(() -> policy.validate("PASSWORD1234", "a@b.test"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("too common");
    }

    @Test
    void rejectsPasswordEqualToEmail() {
        assertThatThrownBy(() -> policy.validate("alice@acme.test", "alice@acme.test"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("email");
    }

    @Test
    void rejectsRepetitivePasswords() {
        assertThatThrownBy(() -> policy.validate("aaaaaaaaaaaaaaaa", "a@b.test"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("repetitive");
    }
}
