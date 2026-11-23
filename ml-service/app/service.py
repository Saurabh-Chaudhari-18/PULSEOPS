"""Glue between the API models and the detection core (framework-free, unit-tested)."""

from __future__ import annotations

from datetime import timedelta

from .detection import Finding, Point, Skip, detect
from .schemas import AnomalyOut, DetectRequest, DetectResponse, SkippedOut


def run(request: DetectRequest) -> DetectResponse:
    anomalies: list[AnomalyOut] = []
    skipped: list[SkippedOut] = []
    for series in request.series:
        points = [Point(p.v, p.n) for p in series.points]
        result = detect(series.metric, points, request.evaluation_points, request.sensitivity)
        if isinstance(result, Skip):
            skipped.append(SkippedOut(key=series.key, reason=result.reason))
        elif isinstance(result, Finding):
            window = series.points[-request.evaluation_points:]
            anomalies.append(AnomalyOut(
                key=series.key, metric=series.metric, anomaly_type=result.anomaly_type,
                severity=result.severity, score=round(result.score, 2), observed=round(result.observed, 6),
                expected=round(result.expected, 6), spread=round(result.spread, 6),
                window_start=window[0].t,
                window_end=window[-1].t + timedelta(seconds=series.step_seconds),
                baseline_points=result.baseline_points, reason=result.reason))
    return DetectResponse(anomalies=anomalies, skipped=skipped, evaluated=len(request.series))
