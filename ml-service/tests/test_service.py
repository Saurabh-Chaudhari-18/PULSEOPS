from datetime import datetime, timedelta, timezone

import pytest
from pydantic import ValidationError

from app.schemas import DetectRequest
from app.service import run

T0 = datetime(2026, 11, 23, 10, 0, tzinfo=timezone.utc)


def series(key, metric, values, samples=40):
    return {"key": key, "metric": metric,
            "points": [{"t": (T0 + timedelta(minutes=i)).isoformat(), "v": v, "n": samples}
                       for i, v in enumerate(values)]}


def test_run_returns_anomalies_skips_and_windows():
    request = DetectRequest.model_validate({"series": [
        series("svc|latency", "response_latency", [12.0] * 60 + [1500.0] * 5),
        series("svc|errors", "error_rate", [0.0] * 65),
        series("new|latency", "response_latency", [12.0] * 8),
    ]})
    response = run(request)
    assert response.evaluated == 3
    assert [a.key for a in response.anomalies] == ["svc|latency"]
    a = response.anomalies[0]
    assert a.window_start == T0 + timedelta(minutes=60)
    assert a.window_end == T0 + timedelta(minutes=65)     # end of the last evaluated minute
    assert a.detector == "robust-zscore-v1"
    assert [s.key for s in response.skipped] == ["new|latency"]


def test_requests_are_validated_and_bounded():
    with pytest.raises(ValidationError):
        DetectRequest.model_validate({"series": [series("k", "cpu", [1.0])]})            # unknown metric
    with pytest.raises(ValidationError):
        DetectRequest.model_validate({"series": [], "unexpected": 1})                    # extra field
    unordered = series("k", "request_rate", [1.0, 2.0])
    unordered["points"].reverse()
    with pytest.raises(ValidationError):
        DetectRequest.model_validate({"series": [unordered]})
    with pytest.raises(ValidationError):
        DetectRequest.model_validate({"series": [series("k", "request_rate", [1.0] * 1441)]})
    with pytest.raises(ValidationError):
        DetectRequest.model_validate({"series": [series(f"k{i}", "request_rate", [1.0]) for i in range(201)]})
