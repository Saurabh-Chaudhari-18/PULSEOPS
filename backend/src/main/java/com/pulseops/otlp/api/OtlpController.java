package com.pulseops.otlp.api;

import java.io.IOException;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import com.google.protobuf.MessageLite;
import com.pulseops.common.config.PulseOpsProperties;
import com.pulseops.common.error.ApiException;
import com.pulseops.ingest.api.IngestController;
import com.pulseops.ingest.service.BoundedBody;
import com.pulseops.otlp.service.OtlpIngestService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * OTLP/HTTP receiver ({@code /otlp/v1/traces|logs|metrics}), binary protobuf only.
 * Authenticated by integration key ({@code X-PulseOps-Key} or {@code Authorization: Bearer}),
 * on the ingestion security chain. Success is 200 with an export response (possibly carrying
 * {@code partial_success}); failures are a protobuf {@code google.rpc.Status}, with
 * {@code Retry-After} on 429/503 so exporters back off.
 */
@RestController
public class OtlpController {

    public static final MediaType PROTOBUF = MediaType.parseMediaType("application/x-protobuf");
    static final String RETRY_AFTER_SECONDS = "30";
    private static final Logger log = LoggerFactory.getLogger(OtlpController.class);

    private final OtlpIngestService otlp;
    private final int maxPayloadBytes;

    public OtlpController(OtlpIngestService otlp, PulseOpsProperties props) {
        this.otlp = otlp;
        this.maxPayloadBytes = props.ingest().maxPayloadBytes();
    }

    @PostMapping("/otlp/v1/traces")
    public ResponseEntity<byte[]> traces(
            @RequestHeader(name = IngestController.KEY_HEADER, required = false) String keyHeader,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(name = HttpHeaders.CONTENT_ENCODING, required = false) String contentEncoding,
            HttpServletRequest request) throws IOException {
        byte[] body = readProtobuf(request);
        return ok(otlp.traces(IngestController.extractKey(keyHeader, authorization), body, contentEncoding));
    }

    @PostMapping("/otlp/v1/logs")
    public ResponseEntity<byte[]> logs(
            @RequestHeader(name = IngestController.KEY_HEADER, required = false) String keyHeader,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(name = HttpHeaders.CONTENT_ENCODING, required = false) String contentEncoding,
            HttpServletRequest request) throws IOException {
        byte[] body = readProtobuf(request);
        return ok(otlp.logs(IngestController.extractKey(keyHeader, authorization), body, contentEncoding));
    }

    @PostMapping("/otlp/v1/metrics")
    public ResponseEntity<byte[]> metrics(
            @RequestHeader(name = IngestController.KEY_HEADER, required = false) String keyHeader,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(name = HttpHeaders.CONTENT_ENCODING, required = false) String contentEncoding,
            HttpServletRequest request) throws IOException {
        byte[] body = readProtobuf(request);
        return ok(otlp.metrics(IngestController.extractKey(keyHeader, authorization), body, contentEncoding));
    }

    private byte[] readProtobuf(HttpServletRequest request) throws IOException {
        if (!isProtobuf(request.getContentType())) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                    "Only OTLP/HTTP binary protobuf is supported (Content-Type: application/x-protobuf); "
                            + "set OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf");
        }
        return BoundedBody.read(request.getInputStream(), request.getContentLengthLong(), maxPayloadBytes);
    }

    static boolean isProtobuf(String contentType) {
        if (contentType == null) {
            return false;
        }
        String base = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return base.equals("application/x-protobuf");
    }

    private static ResponseEntity<byte[]> ok(MessageLite response) {
        return ResponseEntity.ok().contentType(PROTOBUF).body(response.toByteArray());
    }

    /** OTLP clients expect a protobuf Status body, not the JSON error used by the REST API. */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<byte[]> handle(ApiException ex, HttpServletRequest request) {
        if (ex.status().is5xxServerError()) {
            log.warn("OTLP export to {} refused: {} {}", request.getRequestURI(), ex.code(), ex.getMessage());
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.status()).contentType(PROTOBUF);
        if (ex.status() == HttpStatus.TOO_MANY_REQUESTS || ex.status() == HttpStatus.SERVICE_UNAVAILABLE) {
            builder.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return builder.body(OtlpStatus.encode(OtlpStatus.grpcCode(ex.status()), ex.code() + ": " + ex.getMessage()));
    }
}
