# PulseOps ML service

Stateless anomaly detection for PulseOps (ADR-023). The backend sends per-minute metric
series (request rate, p95 latency, error rate, database p95, connection-pool use); the
service compares the newest minutes with each series' robust baseline (median and MAD)
and returns explained findings. It stores nothing and never sees raw telemetry.

## Run locally (Windows PowerShell)

```powershell
cd F:\PULSEOPS\ml-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements-dev.txt
python -m pytest                     # all tests, including the HTTP layer
$env:PULSEOPS_ML_SECRET = "<same 32+ character value as the backend's PULSEOPS_ML_SECRET>"
uvicorn --factory app.main:create_app --host 127.0.0.1 --port 8500
```

`GET /health` is open; `POST /v1/detect` needs the header `X-PulseOps-ML-Secret`.
Bodies over 4 MiB (`PULSEOPS_ML_MAX_BODY_BYTES`), more than 200 series or 1440 points per
series are rejected. API docs endpoints are disabled. `PULSEOPS_ML_ALLOW_INSECURE=true`
skips authentication for local experiments only.

## Detection rules (per series)

| Metric | Direction | Needs, besides robust z ≥ 3.5 (medium sensitivity) |
|---|---|---|
| `response_latency` (p95 ms) | up | ≥ 50 ms and ≥ 1.5× above usual; minutes with < 3 requests ignored |
| `db_duration` (p95 ms) | up | ≥ 20 ms and ≥ 1.5× above usual |
| `error_rate` (0–1) | up | ≥ 2 points above usual and ≥ 5 failed requests in the window |
| `request_rate` (req/s) | up / down | spike: ≥ 2× and +0.2 req/s; drop: to ≤ 50 % from a usual ≥ 0.2 req/s (missing minutes count as 0) |
| `connection_utilization` (0–1) | up | ≥ +20 points and ≥ 1.25× |

At least 30 usable baseline minutes are required; otherwise the series is reported as
skipped with the reason. The evaluation window (default 5 minutes) is summarised by its
median, so one bad minute is not an anomaly but a sustained change is.
