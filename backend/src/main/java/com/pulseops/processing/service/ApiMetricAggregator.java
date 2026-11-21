package com.pulseops.processing.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pulseops.processing.model.TelemetryEvent;

/**
 * Folds HTTP performance events into one-minute aggregates keyed by
 * (route template, method, status class, minute). Each aggregate carries count, server
 * errors, sum/min/max and a {@link LatencyHistogram}, all of which merge exactly with rows
 * already stored for the same minute.
 */
public final class ApiMetricAggregator {

    public static final int BUCKET_SECONDS = 60;

    private ApiMetricAggregator() {
    }

    public record Key(String route, String method, String statusClass, Instant bucketStart) {
    }

    public static final class Aggregate {
        private final Key key;
        private long requests;
        private long errors;
        private double sumMs;
        private double minMs = Double.MAX_VALUE;
        private double maxMs = 0;
        private final LatencyHistogram histogram = new LatencyHistogram();

        Aggregate(Key key) {
            this.key = key;
        }

        void add(TelemetryEvent.Http http) {
            requests++;
            if (http.isServerError()) {
                errors++;
            }
            sumMs += http.durationMs();
            minMs = Math.min(minMs, http.durationMs());
            maxMs = Math.max(maxMs, http.durationMs());
            histogram.record(http.durationMs());
        }

        public Key key() {
            return key;
        }

        public long requests() {
            return requests;
        }

        public long errors() {
            return errors;
        }

        public double sumMs() {
            return sumMs;
        }

        public double minMs() {
            return minMs;
        }

        public double maxMs() {
            return maxMs;
        }

        public long[] histogram() {
            return histogram.toArray();
        }
    }

    public static Collection<Aggregate> aggregate(List<TelemetryEvent> events) {
        Map<Key, Aggregate> byKey = new LinkedHashMap<>();
        for (TelemetryEvent event : events) {
            TelemetryEvent.Http http = event.http();
            if (http == null) {
                continue;
            }
            Key key = new Key(http.route(), http.method(), http.statusClass(),
                    event.occurredAt().truncatedTo(ChronoUnit.MINUTES));
            byKey.computeIfAbsent(key, Aggregate::new).add(http);
        }
        return byKey.values();
    }
}
