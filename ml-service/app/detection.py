"""Anomaly detection over per-minute service metrics.

Design (ADR-023): explainable robust statistics, not a black box.

For each series the newest ``evaluation_points`` points are compared with the
baseline formed by everything before them:

* baseline centre = median, spread = 1.4826 x MAD (median absolute deviation),
  floored so that a perfectly flat baseline cannot turn noise into "infinite" z;
* observed = median of the evaluation window, so one odd minute is not an anomaly
  but a sustained change is;
* score = robust z = (observed - centre) / spread.

An anomaly needs BOTH a high score AND a change that matters in practice (relative
and absolute floors per metric), so tiny services with near-zero variance do not
raise alarms for a 3 ms wobble. Every result carries a human-readable reason.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Optional, Sequence

import numpy as np

MAD_TO_SIGMA = 1.4826


class Metric(str, Enum):
    REQUEST_RATE = "request_rate"            # requests per second
    RESPONSE_LATENCY = "response_latency"    # p95 ms
    ERROR_RATE = "error_rate"                # 5xx fraction 0..1
    DB_DURATION = "db_duration"              # p95 ms
    CONNECTION_UTILIZATION = "connection_utilization"  # used / max, 0..1


class Sensitivity(str, Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"


Z_THRESHOLD = {Sensitivity.LOW: 4.5, Sensitivity.MEDIUM: 3.5, Sensitivity.HIGH: 3.0}


@dataclass(frozen=True)
class Point:
    value: Optional[float]
    samples: Optional[int] = None   # requests/calls behind the value, when meaningful


@dataclass(frozen=True)
class Rules:
    """Practical-significance guards for one metric."""

    direction: str            # "up", or "both" (request rate can also drop)
    spread_floor_abs: float   # minimum spread in the metric's unit
    spread_floor_rel: float   # minimum spread as a fraction of the baseline centre
    min_delta_abs: float      # observed must differ from centre by at least this
    min_ratio: float          # ... and be at least this many times the centre (upward)
    min_samples: int          # points backed by fewer samples are ignored (0 = no check)
    unit: str


RULES = {
    Metric.RESPONSE_LATENCY: Rules("up", 5.0, 0.10, 50.0, 1.5, 3, "ms"),
    Metric.DB_DURATION: Rules("up", 2.0, 0.10, 20.0, 1.5, 3, "ms"),
    Metric.ERROR_RATE: Rules("up", 0.005, 0.25, 0.02, 1.0, 1, ""),
    Metric.REQUEST_RATE: Rules("both", 0.02, 0.10, 0.2, 2.0, 0, "req/s"),
    Metric.CONNECTION_UTILIZATION: Rules("up", 0.02, 0.10, 0.2, 1.25, 0, ""),
}

MIN_BASELINE_POINTS = 30
MAX_SCORE = 1_000.0


@dataclass(frozen=True)
class Finding:
    anomaly_type: str       # spike | drop
    severity: str           # critical | high | medium | low
    score: float
    observed: float
    expected: float
    spread: float
    baseline_points: int
    evaluation_points: int
    reason: str


@dataclass(frozen=True)
class Skip:
    reason: str


def _usable(points: Sequence[Point], rules: Rules) -> np.ndarray:
    values = [
        p.value
        for p in points
        if p.value is not None
        and np.isfinite(p.value)
        and (rules.min_samples == 0 or (p.samples or 0) >= rules.min_samples)
    ]
    return np.asarray(values, dtype=float)


def detect(
    metric: Metric,
    points: Sequence[Point],
    evaluation_points: int = 5,
    sensitivity: Sensitivity = Sensitivity.MEDIUM,
) -> Finding | Skip | None:
    """Return a Finding, a Skip explaining why the series was not judged, or None (normal)."""
    rules = RULES[metric]
    if evaluation_points < 1 or len(points) <= evaluation_points:
        return Skip("not enough points to separate a baseline from the evaluation window")

    baseline = _usable(points[:-evaluation_points], rules)
    recent_points = points[-evaluation_points:]
    recent = _usable(recent_points, rules)
    if baseline.size < MIN_BASELINE_POINTS:
        return Skip(f"insufficient history: {baseline.size} usable baseline points, "
                    f"{MIN_BASELINE_POINTS} needed")
    # A sustained change needs most of the window, not one lucky minute.
    needed = max(1, (evaluation_points + 1) // 2)
    if metric == Metric.REQUEST_RATE:
        # Missing minutes mean no requests at all: that is information for a drop.
        recent = np.asarray([0.0 if p.value is None else p.value for p in recent_points], dtype=float)
    elif recent.size < needed:
        return Skip(f"only {recent.size} usable points in the evaluation window, {needed} needed")

    centre = float(np.median(baseline))
    mad = float(np.median(np.abs(baseline - centre)))
    spread = max(MAD_TO_SIGMA * mad, rules.spread_floor_abs, rules.spread_floor_rel * abs(centre))
    observed = float(np.median(recent))
    z = (observed - centre) / spread
    threshold = Z_THRESHOLD[sensitivity]

    if metric == Metric.ERROR_RATE:
        errors = sum((p.value or 0.0) * (p.samples or 0) for p in recent_points if p.value is not None)
        if errors < 5:
            return None

    if z >= threshold:
        delta = observed - centre
        if delta < rules.min_delta_abs:
            return None
        if centre > 0 and observed < centre * rules.min_ratio:
            return None
        severity = _severity_up(metric, observed, centre)
        return Finding("spike", severity, min(z, MAX_SCORE), observed, centre, spread, int(baseline.size),
                       evaluation_points, _reason(metric, "above", observed, centre, z, rules))

    if rules.direction == "both" and z <= -threshold:
        if centre < 0.2 or observed > centre * 0.5 or centre - observed < rules.min_delta_abs:
            return None
        drop = 1.0 - observed / centre
        severity = "critical" if drop >= 0.9 else "high" if drop >= 0.75 else "medium"
        return Finding("drop", severity, min(-z, MAX_SCORE), observed, centre, spread, int(baseline.size),
                       evaluation_points, _reason(metric, "below", observed, centre, -z, rules))
    return None


def _severity_up(metric: Metric, observed: float, centre: float) -> str:
    if metric == Metric.ERROR_RATE:
        return ("critical" if observed >= 0.25 else "high" if observed >= 0.10
                else "medium" if observed >= 0.05 else "low")
    if metric == Metric.CONNECTION_UTILIZATION:
        return ("critical" if observed >= 0.95 else "high" if observed >= 0.85
                else "medium" if observed >= 0.70 else "low")
    ratio = observed / centre if centre > 0 else float("inf")
    if metric == Metric.REQUEST_RATE:
        return "high" if ratio >= 10 else "medium" if ratio >= 5 else "low"
    return ("critical" if ratio >= 10 else "high" if ratio >= 5
            else "medium" if ratio >= 2.5 else "low")


def _fmt(metric: Metric, value: float) -> str:
    if metric in (Metric.ERROR_RATE, Metric.CONNECTION_UTILIZATION):
        return f"{value * 100:.1f}%"
    if metric == Metric.REQUEST_RATE:
        return f"{value:.2f} req/s"
    # Sub-10 ms values keep a decimal so "usual 0.7 ms (2087x)" reads consistently.
    return f"{value:.1f} ms" if abs(value) < 10 else f"{value:.0f} ms"


_LABEL = {
    Metric.REQUEST_RATE: "Request rate",
    Metric.RESPONSE_LATENCY: "p95 latency",
    Metric.ERROR_RATE: "Error rate",
    Metric.DB_DURATION: "Database p95",
    Metric.CONNECTION_UTILIZATION: "Connection pool utilisation",
}


def _reason(metric: Metric, direction: str, observed: float, centre: float, z: float, rules: Rules) -> str:
    text = f"{_LABEL[metric]} {_fmt(metric, observed)} is far {direction} its usual {_fmt(metric, centre)}"
    if centre > 0 and metric not in (Metric.ERROR_RATE, Metric.CONNECTION_UTILIZATION):
        text += f" ({observed / centre:.1f}x)"
    return text + f"; robust z-score {z:.1f}"
