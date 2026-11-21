package com.pulseops.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseops.testsupport.TestProperties;

import org.junit.jupiter.api.Test;

class JwtConfigTest {

    @Test
    void refusesMissingSecret() {
        assertThatThrownBy(() -> JwtConfig.signingKeyFrom(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PULSEOPS_JWT_SECRET is not set");
        assertThatThrownBy(() -> JwtConfig.signingKeyFrom(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesNonBase64Secret() {
        assertThatThrownBy(() -> JwtConfig.signingKeyFrom("not base64 !!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void refusesShortSecret() {
        assertThatThrownBy(() -> JwtConfig.signingKeyFrom("c2hvcnQ=")) // "short"
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void acceptsStrongSecret() {
        assertThat(JwtConfig.signingKeyFrom(TestProperties.TEST_JWT_SECRET).getAlgorithm()).isEqualTo("HmacSHA256");
    }
}
