package com.pulseops.processing.model;

import java.time.Instant;
import java.util.Map;

/**
 * One-minute aggregate of a metric series (name + attribute set).
 *
 * @param kind          gauge, sum, histogram or summary
 * @param attributesKey canonical "k=v,k2=v2" form used for uniqueness
 * @param count         number of samples (for histograms: number of recorded measurements)
 * @param sum           sum of sample values (for histograms: sum of measurements)
 */
public record MetricPointRecord(
        String name,
        String unit,
        String kind,
        Map<String, Object> attributes,
        String attributesKey,
        Instant bucketStart,
        long count,
        double sum,
        Double min,
        Double max,
        Double last) {

    public MetricPointRecord {
        attributes = Map.copyOf(attributes);
    }
}
