package com.pulseops.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * In-process stand-in for the Python ML service, speaking the same JSON contract
 * (ml-service/tests/test_contract.py): flags every service-level latency series.
 */
public final class StubMl {

    public static final String SECRET = "stub-ml-shared-secret-of-at-least-32-chars";

    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> seriesKeys = new CopyOnWriteArrayList<>();
    private volatile String lastSecret;

    private StubMl(HttpServer server) {
        this.server = server;
    }

    public static StubMl start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubMl stub = new StubMl(server);
            server.createContext("/v1/detect", exchange -> {
                stub.lastSecret = exchange.getRequestHeaders().getFirst("X-PulseOps-ML-Secret");
                byte[] response;
                try (InputStream in = exchange.getRequestBody()) {
                    response = stub.respond(stub.json.readTree(in));
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response);
                }
            });
            server.setExecutor(null);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] respond(JsonNode request) throws IOException {
        List<Map<String, Object>> anomalies = new ArrayList<>();
        for (JsonNode series : request.get("series")) {
            String key = series.get("key").asText();
            seriesKeys.add(key);
            JsonNode points = series.get("points");
            if (!key.endsWith("|response_latency|service") || points.size() < 5) {
                continue;
            }
            Instant start = Instant.parse(points.get(points.size() - 5).get("t").asText());
            Instant end = Instant.parse(points.get(points.size() - 1).get("t").asText()).plusSeconds(60);
            anomalies.add(Map.ofEntries(
                    Map.entry("key", key), Map.entry("metric", "response_latency"),
                    Map.entry("anomaly_type", "spike"), Map.entry("severity", "high"), Map.entry("score", 42.0),
                    Map.entry("observed", 1500.0), Map.entry("expected", 12.0), Map.entry("spread", 5.0),
                    Map.entry("window_start", start.toString()), Map.entry("window_end", end.toString()),
                    Map.entry("baseline_points", 30),
                    Map.entry("reason", "p95 latency 1500 ms is far above its usual 12 ms (125.0x); robust z-score 42.0"),
                    Map.entry("detector", "stub")));
        }
        return json.writeValueAsString(Map.of("anomalies", anomalies, "skipped", List.of(),
                "evaluated", request.get("series").size())).getBytes(StandardCharsets.UTF_8);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<String> seriesKeys() {
        return List.copyOf(seriesKeys);
    }

    public String lastSecret() {
        return lastSecret;
    }
}
