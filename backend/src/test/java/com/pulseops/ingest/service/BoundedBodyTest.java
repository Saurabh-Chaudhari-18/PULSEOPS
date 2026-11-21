package com.pulseops.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import com.pulseops.common.error.ApiException;

import org.junit.jupiter.api.Test;

class BoundedBodyTest {

    @Test
    void acceptsBodiesUpToTheLimit() throws Exception {
        assertThat(BoundedBody.read(stream(10), 10, 10)).hasSize(10);
        assertThat(BoundedBody.read(stream(5), -1, 10)).hasSize(5); // chunked: no Content-Length
    }

    @Test
    void rejectsDeclaredOversizeWithoutReading() {
        InputStream neverRead = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("body must not be read");
            }
        };
        assertThatThrownBy(() -> BoundedBody.read(neverRead, 11, 10))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void rejectsUndeclaredOrLyingOversize() {
        assertThatThrownBy(() -> BoundedBody.read(stream(11), -1, 10))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("PAYLOAD_TOO_LARGE");
        assertThatThrownBy(() -> BoundedBody.read(stream(50), 5, 10))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void rejectsEmptyBody() {
        assertThatThrownBy(() -> BoundedBody.read(stream(0), 0, 10))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("EMPTY_BODY");
    }

    private static InputStream stream(int size) {
        return new ByteArrayInputStream(new byte[size]);
    }
}
