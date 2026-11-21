package com.pulseops.otlp.api;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;

/**
 * Encodes a {@code google.rpc.Status} message — the body OTLP/HTTP requires for 4xx/5xx
 * responses to protobuf requests. Hand-encoded (two fields) to avoid a dependency on the
 * common-protos artifact:
 * <pre>
 * message Status { int32 code = 1; string message = 2; repeated Any details = 3; }
 * </pre>
 */
public final class OtlpStatus {

    private OtlpStatus() {
    }

    public static byte[] encode(int grpcCode, String message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (grpcCode != 0) {
            out.write(0x08);                 // field 1, varint
            writeVarint(out, grpcCode);
        }
        if (message != null && !message.isEmpty()) {
            byte[] utf8 = message.getBytes(StandardCharsets.UTF_8);
            out.write(0x12);                 // field 2, length-delimited
            writeVarint(out, utf8.length);
            out.writeBytes(utf8);
        }
        return out.toByteArray();
    }

    /** The gRPC status code conventionally paired with an HTTP status. */
    public static int grpcCode(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST, PAYLOAD_TOO_LARGE, UNSUPPORTED_MEDIA_TYPE -> 3;   // INVALID_ARGUMENT
            case UNAUTHORIZED -> 16;                                             // UNAUTHENTICATED
            case FORBIDDEN -> 7;                                                 // PERMISSION_DENIED
            case NOT_FOUND -> 5;                                                 // NOT_FOUND
            case TOO_MANY_REQUESTS -> 8;                                         // RESOURCE_EXHAUSTED
            case SERVICE_UNAVAILABLE -> 14;                                      // UNAVAILABLE
            default -> 13;                                                       // INTERNAL
        };
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        long v = value & 0xFFFFFFFFL;   // int32 codes are non-negative here
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }
}
