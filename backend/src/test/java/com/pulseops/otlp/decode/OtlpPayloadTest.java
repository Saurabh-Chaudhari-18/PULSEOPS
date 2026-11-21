package com.pulseops.otlp.decode;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import com.pulseops.common.error.ApiException;

import org.junit.jupiter.api.Test;

class OtlpPayloadTest {

    private static final byte[] DATA = "hello otlp".getBytes(StandardCharsets.UTF_8);

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    private static String errorCode(Runnable call) {
        try {
            call.run();
            return "none";
        } catch (ApiException e) {
            return e.code();
        }
    }

    @Test
    void identityAndGzipAreDecoded() throws IOException {
        assertThat((Object) new String(OtlpPayload.decode(DATA, null, 100), StandardCharsets.UTF_8))
                .as("identity").isEqualTo("hello otlp");
        assertThat((Object) new String(OtlpPayload.decode(gzip(DATA), "gzip", 100), StandardCharsets.UTF_8))
                .as("gzip").isEqualTo("hello otlp");
        assertThat((Object) new String(OtlpPayload.decode(gzip(DATA), " GZIP ", 100), StandardCharsets.UTF_8))
                .as("encoding is case-insensitive").isEqualTo("hello otlp");
    }

    @Test
    void decompressionBombIsStoppedAtTheCap() throws IOException {
        byte[] bomb = gzip(new byte[50_000_000]);   // ~50 KB that inflates to 50 MB
        assertThat((Object) errorCode(() -> OtlpPayload.decode(bomb, "gzip", 16 * 1024 * 1024)))
                .as("bomb").isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void badOrUnsupportedEncodingsAreRejected() {
        assertThat((Object) errorCode(() -> OtlpPayload.decode(DATA, "gzip", 100)))
                .as("not actually gzip").isEqualTo("MALFORMED_GZIP");
        assertThat((Object) errorCode(() -> OtlpPayload.decode(DATA, "br", 100)))
                .as("brotli").isEqualTo("UNSUPPORTED_ENCODING");
    }
}
