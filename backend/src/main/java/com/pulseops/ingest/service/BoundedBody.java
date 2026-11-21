package com.pulseops.ingest.service;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.http.HttpStatus;

import com.pulseops.common.error.ApiException;

/**
 * Reads a request body while enforcing a hard byte limit. The declared Content-Length is
 * checked first (cheap rejection); the stream is still capped because Content-Length may
 * be absent (chunked) or lie. At most {@code maxBytes + 1} bytes are ever buffered.
 */
public final class BoundedBody {

    private BoundedBody() {
    }

    public static byte[] read(InputStream in, long declaredLength, int maxBytes) throws IOException {
        if (declaredLength > maxBytes) {
            throw tooLarge(maxBytes);
        }
        byte[] body = in.readNBytes(maxBytes + 1);
        if (body.length > maxBytes) {
            throw tooLarge(maxBytes);
        }
        if (body.length == 0) {
            throw ApiException.badRequest("EMPTY_BODY", "Request body is empty");
        }
        return body;
    }

    private static ApiException tooLarge(int maxBytes) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                "Request body exceeds " + maxBytes + " bytes; send smaller batches");
    }
}
