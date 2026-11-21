package com.pulseops.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SecureTokensTest {

    @Test
    void tokensAreUrlSafeAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = SecureTokens.newToken(32);
            assertThat(token).matches("^[A-Za-z0-9_-]{43}$"); // 32 bytes base64url, no padding
            assertThat(seen.add(token)).isTrue();
        }
    }

    @Test
    void refusesLowEntropyTokens() {
        assertThatThrownBy(() -> SecureTokens.newToken(16)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sha256HexIsStableKnownVector() {
        assertThat(SecureTokens.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
