import random

import pytest

from app.detection import Finding, Metric, Point, Sensitivity, Skip, detect


def noisy(n, centre, jitter, samples=50, seed=7):
    rnd = random.Random(seed)
    return [Point(max(0.0, centre + rnd.uniform(-jitter, jitter)), samples) for _ in range(n)]


def test_latency_spike_is_detected_with_severity_and_reason():
    points = noisy(60, 12, 4) + [Point(1500, 40)] * 5
    result = detect(Metric.RESPONSE_LATENCY, points)
    assert isinstance(result, Finding)
    assert result.anomaly_type == "spike"
    assert result.severity == "critical"          # >= 10x usual
    assert result.observed == 1500
    assert 8 <= result.expected <= 16
    assert result.score > 100
    assert result.reason.startswith("p95 latency 1500 ms is far above its usual 1")
    assert "x)" in result.reason


def test_normal_noise_and_small_absolute_changes_are_not_anomalies():
    assert detect(Metric.RESPONSE_LATENCY, noisy(65, 12, 4)) is None
    # 12 -> 45 ms is many robust sigmas on a calm service but below the 50 ms practical floor.
    assert detect(Metric.RESPONSE_LATENCY, noisy(60, 12, 1) + [Point(45, 40)] * 5) is None


def test_single_bad_minute_is_not_a_sustained_anomaly():
    points = noisy(60, 12, 4) + [Point(12, 40), Point(12, 40), Point(2000, 40), Point(12, 40), Point(13, 40)]
    assert detect(Metric.RESPONSE_LATENCY, points) is None


def test_minutes_with_too_few_requests_are_ignored():
    sparse_spike = noisy(60, 12, 4) + [Point(5000, 1)] * 5
    result = detect(Metric.RESPONSE_LATENCY, sparse_spike)
    assert isinstance(result, Skip)
    assert "evaluation window" in result.reason


def test_insufficient_history_is_reported_not_guessed():
    result = detect(Metric.RESPONSE_LATENCY, noisy(10, 12, 4) + [Point(1500, 40)] * 5)
    assert isinstance(result, Skip)
    assert result.reason.startswith("insufficient history: 10 usable baseline points")


def test_error_rate_from_zero_baseline():
    points = [Point(0.0, 30)] * 60 + [Point(0.5, 30)] * 5
    result = detect(Metric.ERROR_RATE, points)
    assert isinstance(result, Finding)
    assert result.severity == "critical"          # 50 % >= 25 %
    assert result.reason == "Error rate 50.0% is far above its usual 0.0%; robust z-score 100.0"


def test_error_rate_needs_enough_failed_requests():
    # 50 % of 2 requests per minute = 5 errors in 5 minutes is the minimum; 4 is not enough.
    points = [Point(0.0, 30)] * 60 + [Point(0.5, 2)] * 4 + [Point(0.0, 2)]
    assert detect(Metric.ERROR_RATE, points) is None


def test_traffic_drop_counts_missing_minutes_as_zero():
    points = noisy(60, 2.0, 0.4) + [Point(None)] * 5
    result = detect(Metric.REQUEST_RATE, points)
    assert isinstance(result, Finding)
    assert result.anomaly_type == "drop"
    assert result.severity == "critical"
    assert result.observed == 0


def test_low_traffic_services_do_not_alarm_on_drops():
    points = noisy(60, 0.1, 0.05) + [Point(0.0)] * 5
    assert detect(Metric.REQUEST_RATE, points) is None


def test_traffic_spike():
    result = detect(Metric.REQUEST_RATE, noisy(60, 2.0, 0.3) + [Point(25.0)] * 5)
    assert isinstance(result, Finding) and result.anomaly_type == "spike" and result.severity == "high"


def test_database_slowdown():
    result = detect(Metric.DB_DURATION, noisy(60, 3, 2) + [Point(1514, 30)] * 5)
    assert isinstance(result, Finding)
    assert result.reason.startswith("Database p95 1514 ms is far above its usual")


@pytest.mark.parametrize("sensitivity,expected", [
    (Sensitivity.HIGH, True), (Sensitivity.MEDIUM, False), (Sensitivity.LOW, False)])
def test_sensitivity_changes_the_threshold(sensitivity, expected):
    # Baseline 85..115 ms (MAD 10 -> spread 14.8); 151 ms is z 3.44: over 3.0, under 3.5.
    base = [Point(100 + (i % 7 - 3) * 5, 50) for i in range(60)]
    result = detect(Metric.RESPONSE_LATENCY, base + [Point(151, 50)] * 5, sensitivity=sensitivity)
    assert isinstance(result, Finding) is expected


def test_flat_baseline_does_not_explode_the_score():
    result = detect(Metric.RESPONSE_LATENCY, [Point(10.0, 50)] * 60 + [Point(70.0, 50)] * 5)
    assert isinstance(result, Finding)
    assert result.spread == 5.0          # absolute floor, not a zero MAD
    assert result.score == 12.0


def test_non_finite_values_are_ignored():
    points = [Point(float("nan"), 50)] * 3 + noisy(60, 12, 4) + [Point(float("inf"), 50)] + [Point(12, 50)] * 5
    assert detect(Metric.RESPONSE_LATENCY, points) is None


def test_small_baselines_keep_a_decimal_in_the_reason():
    result = detect(Metric.DB_DURATION, [Point(0.7, 30)] * 60 + [Point(1514, 30)] * 5)
    assert result.reason.startswith("Database p95 1514 ms is far above its usual 0.7 ms (2162.9x)")



def test_connection_pool_use_is_reported_only_when_high():
    # Live finding on 2026-11-24: 0% -> 20% during a slow-database fault was flagged; at that
    # level the pool is healthy, so it is noise. From 50% it is worth reporting.
    assert detect(Metric.CONNECTION_UTILIZATION, [Point(0.0)] * 60 + [Point(0.2)] * 5) is None
    result = detect(Metric.CONNECTION_UTILIZATION, [Point(0.05)] * 60 + [Point(0.9)] * 5)
    assert isinstance(result, Finding)
    assert result.severity == "high"
    assert result.reason.startswith("Connection pool utilisation 90.0% is far above its usual 5.0%")
