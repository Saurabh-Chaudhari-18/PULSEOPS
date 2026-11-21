package com.pulseops.sample.orders.chaos;

import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

/**
 * Runtime-adjustable faults, so PulseOps can be shown detecting real problems:
 * added request latency, a failure rate in the inventory dependency, and slow database
 * queries. All off by default.
 */
@Component
public class ChaosSettings {

    public record Snapshot(long latencyMs, double inventoryErrorRate, long slowDbMs) {
    }

    private volatile Snapshot current = new Snapshot(0, 0, 0);

    public Snapshot get() {
        return current;
    }

    public void set(Snapshot snapshot) {
        if (snapshot.latencyMs() < 0 || snapshot.latencyMs() > 30_000
                || snapshot.slowDbMs() < 0 || snapshot.slowDbMs() > 30_000
                || snapshot.inventoryErrorRate() < 0 || snapshot.inventoryErrorRate() > 1) {
            throw new IllegalArgumentException("latencyMs/slowDbMs must be 0-30000 and inventoryErrorRate 0-1");
        }
        current = snapshot;
    }

    public static Snapshot preset(String name) {
        return switch (name) {
            case "normal" -> new Snapshot(0, 0, 0);
            case "latency" -> new Snapshot(800, 0, 0);
            case "errors" -> new Snapshot(0, 0.5, 0);
            case "slow-db" -> new Snapshot(0, 0, 1_500);
            case "outage" -> new Snapshot(300, 0.9, 1_000);
            default -> throw new IllegalArgumentException(
                    "Unknown preset '" + name + "' (normal, latency, errors, slow-db, outage)");
        };
    }

    public void maybeDelay() {
        sleep(current.latencyMs());
    }

    public boolean shouldFail() {
        double rate = current.inventoryErrorRate();
        return rate > 0 && ThreadLocalRandom.current().nextDouble() < rate;
    }

    static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
