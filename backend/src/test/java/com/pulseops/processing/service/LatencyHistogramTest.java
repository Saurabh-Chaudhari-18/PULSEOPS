package com.pulseops.processing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LatencyHistogramTest {

    @Test
    void bucketsAreInclusiveUpperBounds() {
        assertThat(LatencyHistogram.bucketFor(0.5)).isZero();
        assertThat(LatencyHistogram.bucketFor(1.0)).isZero();
        assertThat(LatencyHistogram.bucketFor(1.01)).isEqualTo(1);
        assertThat(LatencyHistogram.bucketFor(999_999)).isEqualTo(LatencyHistogram.BOUNDS_MS.length);
    }

    @Test
    void percentilesLandInTheRightBuckets() {
        LatencyHistogram h = new LatencyHistogram();
        for (int i = 0; i < 90; i++) {
            h.record(20);
        }
        for (int i = 0; i < 10; i++) {
            h.record(900);
        }
        assertThat(h.total()).isEqualTo(100);
        assertThat(h.percentile(0.50, 900.0)).isBetween(10.0, 25.0);
        assertThat(h.percentile(0.95, 900.0)).isBetween(750.0, 900.0);
        assertThat(h.percentile(0.99, 900.0)).isGreaterThanOrEqualTo(h.percentile(0.95, 900.0));
    }

    @Test
    void emptyHistogramHasNoPercentile() {
        assertThat(new LatencyHistogram().percentile(0.5, null)).isNull();
    }

    @Test
    void overflowBucketReportsMaxObserved() {
        LatencyHistogram h = new LatencyHistogram();
        h.record(120_000);
        assertThat(h.percentile(0.5, 120_000.0)).isEqualTo(120_000.0);
        assertThat(h.percentile(0.5, null)).isEqualTo(60_000.0);
    }

    @Test
    void mergeIsElementWiseAndPadsShortArrays() {
        long[] merged = LatencyHistogram.of(new long[]{1, 2}).merge(LatencyHistogram.of(new long[]{0, 3, 4})).toArray();
        assertThat(merged).hasSize(LatencyHistogram.SIZE).startsWith(1, 5, 4);
    }

    @Test
    void rejectsInvalidQuantiles() {
        assertThatThrownBy(() -> new LatencyHistogram().percentile(0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LatencyHistogram().percentile(1.5, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
