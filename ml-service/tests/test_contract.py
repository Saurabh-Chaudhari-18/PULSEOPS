"""The exact JSON shape the Spring backend (MlClient) sends and expects back."""

import json

from app.schemas import DetectRequest
from app.service import run


def backend_payload():
    # Jackson: Instants as ISO-8601 with Z, null values omitted (JsonInclude NON_NULL), snake_case names.
    points = []
    for i in range(65):
        minute = f"2026-11-23T09:{i:02d}:00Z" if i < 60 else f"2026-11-23T10:{i - 60:02d}:00Z"
        if i == 3:
            points.append({"t": minute, "n": 0})              # minute without requests: no value
        else:
            points.append({"t": minute, "v": 12.0 if i < 60 else 1514.0, "n": 40})
    return json.dumps({
        "series": [{"key": "6f1c…|response_latency|GET /api/orders/{id}", "metric": "response_latency",
                    "step_seconds": 60, "points": points}],
        "evaluation_points": 5,
        "sensitivity": "medium",
    })


def test_backend_request_is_accepted_and_response_has_the_fields_the_backend_reads():
    response = run(DetectRequest.model_validate_json(backend_payload()))
    out = json.loads(response.model_dump_json())
    finding = out["anomalies"][0]
    for field in ("key", "metric", "anomaly_type", "severity", "score", "observed", "expected", "spread",
                  "window_start", "window_end", "baseline_points", "reason", "detector"):
        assert field in finding
    assert finding["key"] == "6f1c…|response_latency|GET /api/orders/{id}"
    assert finding["window_start"] == "2026-11-23T10:00:00Z"
    assert finding["window_end"] == "2026-11-23T10:05:00Z"
    assert finding["baseline_points"] == 59        # the empty minute is not part of the baseline
