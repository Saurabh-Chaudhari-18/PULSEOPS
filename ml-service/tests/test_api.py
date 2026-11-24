"""HTTP layer. Needs FastAPI and httpx (requirements-dev.txt); skipped where they are absent."""

import pytest

pytest.importorskip("fastapi")
pytest.importorskip("httpx")

from fastapi.testclient import TestClient  # noqa: E402

from app.config import Settings  # noqa: E402
from app.main import create_app  # noqa: E402

SECRET = "s" * 40


@pytest.fixture()
def client():
    return TestClient(create_app(Settings(SECRET, False, 10_000)))


def body():
    points = [{"t": f"2026-11-23T10:{i:02d}:00Z", "v": 12.0 if i < 55 else 1500.0, "n": 40} for i in range(60)]
    return {"series": [{"key": "svc|latency", "metric": "response_latency", "points": points}]}


def test_health_needs_no_secret(client):
    assert client.get("/health").json()["status"] == "UP"


def test_detect_requires_the_shared_secret(client):
    assert client.post("/v1/detect", json=body()).status_code == 401
    assert client.post("/v1/detect", json=body(), headers={"X-PulseOps-ML-Secret": "wrong"}).status_code == 401


def test_detect_returns_findings(client):
    response = client.post("/v1/detect", json=body(), headers={"X-PulseOps-ML-Secret": SECRET})
    assert response.status_code == 200
    assert response.json()["anomalies"][0]["severity"] == "critical"


def test_invalid_and_oversized_bodies_are_rejected(client):
    headers = {"X-PulseOps-ML-Secret": SECRET}
    assert client.post("/v1/detect", json={"series": "nope"}, headers=headers).status_code == 422
    big = {"series": [{"key": "k" * 300, "metric": "request_rate",
                       "points": [{"t": "2026-11-23T10:00:00Z", "v": 1.0}] * 600}]}   # ~22 KB > 10 KB limit
    assert client.post("/v1/detect", json=big, headers=headers).status_code == 413


def test_chunked_bodies_without_content_length_are_limited_too(client):
    headers = {"X-PulseOps-ML-Secret": SECRET, "Content-Type": "application/json"}

    def chunks():
        yield b'{"series": ['
        for _ in range(2_000):
            yield b'{"key": "k", "metric": "request_rate", "points": []},'
        yield b'{"key": "k", "metric": "request_rate", "points": []}]}'

    assert client.post("/v1/detect", content=chunks(), headers=headers).status_code == 413


def test_insecure_settings_are_refused_without_opt_in(monkeypatch):
    monkeypatch.setenv("PULSEOPS_ML_SECRET", "short")
    monkeypatch.delenv("PULSEOPS_ML_ALLOW_INSECURE", raising=False)
    with pytest.raises(RuntimeError):
        Settings.from_env()


def test_docs_are_not_exposed(client):
    assert client.get("/docs").status_code == 404
    assert client.get("/openapi.json").status_code == 404
