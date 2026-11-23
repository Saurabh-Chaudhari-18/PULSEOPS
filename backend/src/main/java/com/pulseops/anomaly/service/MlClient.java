package com.pulseops.anomaly.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pulseops.common.config.PulseOpsProperties;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** HTTP client for the stateless ML service ({@code POST /v1/detect}). */
@Component
public class MlClient {

    static final String SECRET_HEADER = "X-PulseOps-ML-Secret";

    private final RestClient http;
    private final String secret;

    public MlClient(RestClient.Builder builder, PulseOpsProperties props) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.http = builder.baseUrl(props.ml().baseUrl()).requestFactory(factory).build();
        this.secret = props.ml().sharedSecret() == null ? "" : props.ml().sharedSecret();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Point(Instant t, Double v, Long n) {
    }

    public record Series(String key, String metric, @JsonProperty("step_seconds") int stepSeconds,
                         List<Point> points) {
    }

    public record DetectRequest(List<Series> series, @JsonProperty("evaluation_points") int evaluationPoints,
                                String sensitivity) {
    }

    public record Finding(String key, String metric, @JsonProperty("anomaly_type") String anomalyType,
                          String severity, double score, double observed, double expected, double spread,
                          @JsonProperty("window_start") Instant windowStart,
                          @JsonProperty("window_end") Instant windowEnd,
                          @JsonProperty("baseline_points") int baselinePoints, String reason, String detector) {
    }

    public record Skipped(String key, String reason) {
    }

    public record DetectResponse(List<Finding> anomalies, List<Skipped> skipped, int evaluated) {
    }

    public DetectResponse detect(DetectRequest request) {
        return http.post().uri("/v1/detect")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SECRET_HEADER, secret)
                .body(request)
                .retrieve()
                .body(DetectResponse.class);
    }
}
