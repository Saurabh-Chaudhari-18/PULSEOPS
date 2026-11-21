package com.pulseops.otlp.decode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

import com.pulseops.common.error.ApiException;

import org.springframework.http.HttpStatus;

/**
 * Request-body decoding for OTLP/HTTP: optional gzip ({@code Content-Encoding: gzip}, which
 * OpenTelemetry exporters can enable) with a hard cap on the DECOMPRESSED size, so a small
 * compressed "zip bomb" cannot exhaust memory.
 */
public final class OtlpPayload {

    private OtlpPayload() {
    }

    public static byte[] decode(byte[] body, String contentEncoding, int maxDecompressedBytes) {
        if (contentEncoding == null || contentEncoding.isBlank() || "identity".equalsIgnoreCase(contentEncoding.trim())) {
            return body;
        }
        if (!"gzip".equalsIgnoreCase(contentEncoding.trim())) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_ENCODING",
                    "Content-Encoding must be gzip or identity");
        }
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            byte[] out = in.readNBytes(maxDecompressedBytes + 1);
            if (out.length > maxDecompressedBytes) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                        "Decompressed body exceeds " + maxDecompressedBytes + " bytes");
            }
            return out;
        } catch (ZipException e) {
            throw ApiException.badRequest("MALFORMED_GZIP", "Body is not valid gzip");
        } catch (IOException e) {
            throw ApiException.badRequest("MALFORMED_GZIP", "Body could not be decompressed");
        }
    }
}
