package com.pulseops.processing.service;

import java.util.Arrays;

/**
 * Fixed-bucket latency histogram (ADR-014). Bucket {@code i} counts durations
 * {@code <= BOUNDS_MS[i]} (and above the previous bound); the extra last bucket counts
 * everything above the largest bound. Histograms merge by element-wise addition, which is
 * what makes percentiles over arbitrary time ranges correct — averaging percentiles is not.
 *
 * <p>Percentiles are estimated by linear interpolation inside the bucket that contains the
 * requested rank; the error is bounded by the bucket width (≈ 25–50% at each step).</p>
 */
public final class LatencyHistogram {

    /** Upper bounds in milliseconds. Never reorder or change: stored rows depend on them. */
    public static final double[] BOUNDS_MS = {
            1, 2, 5, 10, 25, 50, 75, 100, 150, 200, 300, 400, 500, 750,
            1_000, 1_500, 2_000, 3_000, 5_000, 7_500, 10_000, 15_000, 30_000, 60_000};

    public static final int SIZE = BOUNDS_MS.length + 1;

    private final long[] counts;

    public LatencyHistogram() {
        this.counts = new long[SIZE];
    }

    private LatencyHistogram(long[] counts) {
        this.counts = counts;
    }

    /** Wraps stored counts (shorter arrays — e.g. from an empty row — are zero-padded). */
    public static LatencyHistogram of(long[] stored) {
        return new LatencyHistogram(Arrays.copyOf(stored == null ? new long[0] : stored, SIZE));
    }

    public static int bucketFor(double durationMs) {
        for (int i = 0; i < BOUNDS_MS.length; i++) {
            if (durationMs <= BOUNDS_MS[i]) {
                return i;
            }
        }
        return BOUNDS_MS.length;
    }

    public void record(double durationMs) {
        counts[bucketFor(durationMs)]++;
    }

    public LatencyHistogram merge(LatencyHistogram other) {
        long[] sum = new long[SIZE];
        for (int i = 0; i < SIZE; i++) {
            sum[i] = counts[i] + other.counts[i];
        }
        return new LatencyHistogram(sum);
    }

    public long total() {
        long t = 0;
        for (long c : counts) {
            t += c;
        }
        return t;
    }

    /**
     * Estimated percentile for {@code q} in (0, 1], or null for an empty histogram.
     * Values in the overflow bucket are reported as {@code maxObservedMs} when known,
     * otherwise as the largest bound.
     */
    public Double percentile(double q, Double maxObservedMs) {
        if (q <= 0 || q > 1) {
            throw new IllegalArgumentException("q must be in (0, 1]");
        }
        long total = total();
        if (total == 0) {
            return null;
        }
        double rank = q * total;
        long cumulative = 0;
        for (int i = 0; i < SIZE; i++) {
            long c = counts[i];
            if (c == 0) {
                continue;
            }
            if (cumulative + c >= rank) {
                if (i == BOUNDS_MS.length) {
                    return maxObservedMs != null ? maxObservedMs : BOUNDS_MS[BOUNDS_MS.length - 1];
                }
                double lower = i == 0 ? 0 : BOUNDS_MS[i - 1];
                double upper = BOUNDS_MS[i];
                double fraction = (rank - cumulative) / c;
                double estimate = lower + fraction * (upper - lower);
                return maxObservedMs != null ? Math.min(estimate, maxObservedMs) : estimate;
            }
            cumulative += c;
        }
        return BOUNDS_MS[BOUNDS_MS.length - 1];
    }

    public long[] toArray() {
        return counts.clone();
    }
}
