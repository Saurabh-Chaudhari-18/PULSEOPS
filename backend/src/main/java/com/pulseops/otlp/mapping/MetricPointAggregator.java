package com.pulseops.otlp.mapping;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.pulseops.processing.model.MetricPointRecord;

/**
 * Downsamples OTLP metric data points to one record per (metric, attribute set, minute),
 * bounding series cardinality per request. Cumulative sums are stored as observed values
 * (min/max/last/avg of the counter), histograms as count + sum.
 */
public final class MetricPointAggregator {

    public static final int MAX_SERIES_PER_REQUEST = 2_000;
    static final int MAX_POINT_ATTRIBUTES = 10;
    static final int MAX_KEY_LENGTH = 1_000;

    private final Map<String, Acc> series = new LinkedHashMap<>();
    private int droppedPoints;

    private static final class Acc {
        final String name;
        final String unit;
        final String kind;
        final Map<String, Object> attributes;
        final String attributesKey;
        final Instant bucket;
        long count;
        double sum;
        Double min;
        Double max;
        Double last;
        Instant lastTime;

        Acc(String name, String unit, String kind, Map<String, Object> attributes, String key, Instant bucket) {
            this.name = name;
            this.unit = unit;
            this.kind = kind;
            this.attributes = attributes;
            this.attributesKey = key;
            this.bucket = bucket;
        }
    }

    /** A gauge or sum sample. */
    public void addValue(String name, String unit, String kind, Map<String, Object> rawAttributes, Instant time,
                         double value) {
        if (!Double.isFinite(value)) {
            droppedPoints++;
            return;
        }
        Acc acc = accumulator(name, unit, kind, rawAttributes, time);
        if (acc == null) {
            return;
        }
        acc.count++;
        acc.sum += value;
        acc.min = acc.min == null ? value : Math.min(acc.min, value);
        acc.max = acc.max == null ? value : Math.max(acc.max, value);
        if (acc.lastTime == null || !time.isBefore(acc.lastTime)) {
            acc.last = value;
            acc.lastTime = time;
        }
    }

    /** A histogram or summary point (count and sum of the underlying measurements). */
    public void addDistribution(String name, String unit, String kind, Map<String, Object> rawAttributes,
                                Instant time, long count, double sum, Double min, Double max) {
        if (count < 0 || !Double.isFinite(sum)) {
            droppedPoints++;
            return;
        }
        Acc acc = accumulator(name, unit, kind, rawAttributes, time);
        if (acc == null) {
            return;
        }
        acc.count += count;
        acc.sum += sum;
        if (min != null && Double.isFinite(min)) {
            acc.min = acc.min == null ? min : Math.min(acc.min, min);
        }
        if (max != null && Double.isFinite(max)) {
            acc.max = acc.max == null ? max : Math.max(acc.max, max);
        }
    }

    public List<MetricPointRecord> results() {
        List<MetricPointRecord> out = new ArrayList<>(series.size());
        for (Acc a : series.values()) {
            out.add(new MetricPointRecord(a.name, a.unit, a.kind, a.attributes, a.attributesKey, a.bucket,
                    a.count, a.sum, a.min, a.max, a.last));
        }
        return out;
    }

    public int droppedPoints() {
        return droppedPoints;
    }

    private Acc accumulator(String name, String unit, String kind, Map<String, Object> rawAttributes, Instant time) {
        if (name == null || name.isBlank() || name.length() > 255) {
            droppedPoints++;
            return null;
        }
        Map<String, Object> attributes = OtlpAttributes.sanitize(rawAttributes, MAX_POINT_ATTRIBUTES);
        String attributesKey = canonicalKey(attributes);
        Instant bucket = time.truncatedTo(ChronoUnit.MINUTES);
        String id = name + '\u0000' + attributesKey + '\u0000' + bucket;
        Acc acc = series.get(id);
        if (acc == null) {
            if (series.size() >= MAX_SERIES_PER_REQUEST) {
                droppedPoints++;
                return null;
            }
            acc = new Acc(name, unit == null || unit.isBlank() ? null : OtlpAttributes.truncate(unit, 64), kind,
                    attributes, attributesKey, bucket);
            series.put(id, acc);
        }
        return acc;
    }

    static String canonicalKey(Map<String, Object> attributes) {
        String key = new TreeMap<>(attributes).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
        return key.length() <= MAX_KEY_LENGTH ? key : key.substring(0, MAX_KEY_LENGTH);
    }
}
