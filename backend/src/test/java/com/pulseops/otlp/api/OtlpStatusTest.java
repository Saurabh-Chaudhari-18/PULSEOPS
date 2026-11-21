package com.pulseops.otlp.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** google.rpc.Status wire format: field 1 varint (code), field 2 length-delimited (message). */
class OtlpStatusTest {

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    @Test
    void encodesCodeAndMessage() {
        assertThat((Object) hex(OtlpStatus.encode(3, "x"))).as("code 3, 'x'").isEqualTo("0803120178");
        assertThat((Object) hex(OtlpStatus.encode(16, ""))).as("empty message omitted").isEqualTo("0810");
        assertThat((Object) hex(OtlpStatus.encode(0, null))).as("defaults omitted").isEqualTo("");
    }

    @Test
    void messageLengthUsesMultiByteVarint() {
        byte[] encoded = OtlpStatus.encode(14, "y".repeat(200));
        assertThat((Object) hex(java.util.Arrays.copyOf(encoded, 5))).as("200 = 0xC8 0x01")
                .isEqualTo("080e12c801");
        assertThat((Object) encoded.length).as("total length").isEqualTo(205);
    }

    @Test
    void mapsHttpStatusesToGrpcCodes() {
        assertThat((Object) OtlpStatus.grpcCode(HttpStatus.BAD_REQUEST)).isEqualTo(3);
        assertThat((Object) OtlpStatus.grpcCode(HttpStatus.UNAUTHORIZED)).isEqualTo(16);
        assertThat((Object) OtlpStatus.grpcCode(HttpStatus.FORBIDDEN)).isEqualTo(7);
        assertThat((Object) OtlpStatus.grpcCode(HttpStatus.NOT_FOUND)).isEqualTo(5);
        assertThat((Object) OtlpStatus.grpcCode(HttpStatus.TOO_MANY_REQUESTS)).isEqualTo(8);
        assertThat((Object) OtlpStatus.grpcCode(HttpStatus.SERVICE_UNAVAILABLE)).isEqualTo(14);
        assertThat((Object) OtlpStatus.grpcCode(HttpStatus.INTERNAL_SERVER_ERROR)).isEqualTo(13);
    }
}
