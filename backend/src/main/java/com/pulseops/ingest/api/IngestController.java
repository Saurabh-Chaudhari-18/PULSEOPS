package com.pulseops.ingest.api;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;

import com.pulseops.common.config.PulseOpsProperties;
import com.pulseops.common.error.ApiException;
import com.pulseops.ingest.service.BoundedBody;
import com.pulseops.ingest.service.IngestService;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST telemetry ingestion. Authenticated by integration key (header {@code X-PulseOps-Key},
 * or {@code Authorization: Bearer po_...}), not by user JWT — this path has its own
 * security filter chain. Returns 202 once the batch is validated and queued.
 */
@RestController
public class IngestController {

    public static final String KEY_HEADER = "X-PulseOps-Key";

    private final IngestService ingestService;
    private final int maxPayloadBytes;

    public IngestController(IngestService ingestService, PulseOpsProperties props) {
        this.ingestService = ingestService;
        this.maxPayloadBytes = props.ingest().maxPayloadBytes();
    }

    @PostMapping(path = "/api/v1/ingest/v1/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestRequests.Accepted> ingestEvents(
            @RequestHeader(name = KEY_HEADER, required = false) String keyHeader,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(name = HttpHeaders.CONTENT_ENCODING, required = false) String contentEncoding,
            HttpServletRequest request) throws IOException {
        if (contentEncoding != null && !contentEncoding.isBlank() && !"identity".equalsIgnoreCase(contentEncoding)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_ENCODING",
                    "Compressed request bodies are not supported yet; send uncompressed JSON");
        }
        byte[] body = BoundedBody.read(request.getInputStream(), request.getContentLengthLong(), maxPayloadBytes);
        IngestRequests.Accepted accepted = ingestService.accept(extractKey(keyHeader, authorization), body);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }

    static String extractKey(String keyHeader, String authorization) {
        if (keyHeader != null && !keyHeader.isBlank()) {
            return keyHeader.trim();
        }
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return null;
    }
}
