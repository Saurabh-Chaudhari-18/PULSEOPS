package com.pulseops.sample.orders.traffic;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Sends a realistic mix of requests to this service over HTTP so there is steady telemetry
 * without an external load tool: reads (some for missing orders → 404), listings, new orders
 * (some declined → 402, some for unknown products → 422) and the slower sales report.
 */
@Component
public class TrafficGenerator {

    private static final Logger log = LoggerFactory.getLogger(TrafficGenerator.class);
    private static final String[] SKUS = {"KB-101", "MS-202", "MN-303", "LP-404", "XX-999"};

    private final double requestsPerSecond;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private ScheduledExecutorService scheduler;
    private String base;

    public TrafficGenerator(@Value("${sample.traffic.requests-per-second:2}") double requestsPerSecond) {
        this.requestsPerSecond = requestsPerSecond;
    }

    @EventListener
    public void start(WebServerInitializedEvent event) {
        if (requestsPerSecond <= 0) {
            log.info("Traffic generator disabled (sample.traffic.requests-per-second=0)");
            return;
        }
        base = "http://127.0.0.1:" + event.getWebServer().getPort();
        long periodMicros = (long) (1_000_000 / Math.min(requestsPerSecond, 50));
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "traffic-generator");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::sendOne, 5_000_000, periodMicros, TimeUnit.MICROSECONDS);
        log.info("Traffic generator sending ~{} requests/second to {}", requestsPerSecond, base);
    }

    void sendOne() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int roll = random.nextInt(100);
        HttpRequest request;
        if (roll < 45) {
            request = get("/api/orders/" + (1 + random.nextInt(60)));      // ids > 50 are 404s
        } else if (roll < 65) {
            request = get("/api/orders?limit=20" + (random.nextBoolean() ? "&status=PLACED" : ""));
        } else if (roll < 92) {
            String body = "{\"customerEmail\":\"shopper" + random.nextInt(20) + "@example.com\",\"sku\":\""
                    + SKUS[random.nextInt(SKUS.length)] + "\",\"quantity\":" + (1 + random.nextInt(3)) + "}";
            request = HttpRequest.newBuilder(URI.create(base + "/api/orders"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        } else {
            request = get("/api/orders/report");
        }
        // Asynchronous, so a slow endpoint (e.g. with the slow-db fault on) does not throttle the mix.
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(e -> {
                    log.debug("Traffic request failed: {}", e.toString());
                    return null;
                });
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30)).GET().build();
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
