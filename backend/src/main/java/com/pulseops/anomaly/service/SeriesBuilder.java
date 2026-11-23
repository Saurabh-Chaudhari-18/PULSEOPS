package com.pulseops.anomaly.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.pulseops.processing.service.LatencyHistogram;

/**
 * Turns per-minute aggregates into the series the ML service judges. Pure functions:
 * absent data stays null (never 0 ms), except request rate, where a minute without
 * requests after the service's first request really is 0 requests per second.
 */
public final class SeriesBuilder {

    public static final String SERVICE_SCOPE = "service";

    private SeriesBuilder() {
    }

    /** Request statistics of one minute. */
    public record RequestMinute(long requests, long errors, Double maxMs, long[] buckets) {
    }

    /** What a series key refers to, to map findings back. */
    public record SeriesRef(UUID serviceId, String metric, String scope) {

        public String key() {
            return serviceId + "|" + metric + "|" + scope;
        }
    }

    public record Built(MlClient.Series series, SeriesRef ref) {
    }

    /** Request rate (req/s), p95 latency (ms) and 5xx error rate for one scope. */
    public static List<Built> requests(UUID serviceId, String scope, List<Instant> minutes,
                                       Map<Instant, RequestMinute> data) {
        List<MlClient.Point> rate = new ArrayList<>(minutes.size());
        List<MlClient.Point> latency = new ArrayList<>(minutes.size());
        List<MlClient.Point> errors = new ArrayList<>(minutes.size());
        boolean started = false;
        for (Instant t : minutes) {
            RequestMinute m = data.get(t);
            if (m == null || m.requests() == 0) {
                rate.add(new MlClient.Point(t, started ? 0.0 : null, null));
                latency.add(new MlClient.Point(t, null, 0L));
                errors.add(new MlClient.Point(t, null, 0L));
                continue;
            }
            started = true;
            rate.add(new MlClient.Point(t, m.requests() / 60.0, m.requests()));
            latency.add(new MlClient.Point(t, LatencyHistogram.of(m.buckets()).percentile(0.95, m.maxMs()),
                    m.requests()));
            errors.add(new MlClient.Point(t, (double) m.errors() / m.requests(), m.requests()));
        }
        if (!started) {
            return List.of();
        }
        return List.of(
                built(serviceId, "request_rate", scope, rate),
                built(serviceId, "response_latency", scope, latency),
                built(serviceId, "error_rate", scope, errors));
    }

    /** Database p95 per minute: (calls, p95). */
    public static List<Built> database(UUID serviceId, List<Instant> minutes, Map<Instant, double[]> data) {
        if (data.isEmpty()) {
            return List.of();
        }
        List<MlClient.Point> points = new ArrayList<>(minutes.size());
        for (Instant t : minutes) {
            double[] v = data.get(t);
            points.add(v == null ? new MlClient.Point(t, null, 0L)
                    : new MlClient.Point(t, v[1], (long) v[0]));
        }
        return List.of(built(serviceId, "db_duration", SERVICE_SCOPE, points));
    }

    /** Pool utilisation per minute: (used, max). Minutes without a positive max are absent. */
    public static List<Built> pool(UUID serviceId, List<Instant> minutes, Map<Instant, double[]> data) {
        List<MlClient.Point> points = new ArrayList<>(minutes.size());
        boolean any = false;
        for (Instant t : minutes) {
            double[] v = data.get(t);
            if (v == null || v[1] <= 0 || Double.isNaN(v[0])) {
                points.add(new MlClient.Point(t, null, null));
            } else {
                any = true;
                points.add(new MlClient.Point(t, Math.min(1.0, v[0] / v[1]), null));
            }
        }
        return any ? List.of(built(serviceId, "connection_utilization", SERVICE_SCOPE, points)) : List.of();
    }

    /** Minute starts in [from, to). */
    public static List<Instant> minutes(Instant from, Instant to) {
        List<Instant> out = new ArrayList<>();
        for (Instant t = from; t.isBefore(to); t = t.plusSeconds(60)) {
            out.add(t);
        }
        return out;
    }

    private static Built built(UUID serviceId, String metric, String scope, List<MlClient.Point> points) {
        SeriesRef ref = new SeriesRef(serviceId, metric, scope);
        return new Built(new MlClient.Series(ref.key(), metric, 60, List.copyOf(points)), ref);
    }
}
