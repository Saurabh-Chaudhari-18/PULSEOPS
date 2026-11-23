# Sample order service — the PulseOps customer journey, end to end

An ordinary Spring Boot service (orders, an inventory API called over HTTP, a simulated
payment provider, an H2 database) with **no PulseOps or OpenTelemetry code**. Telemetry
comes only from attaching the official OpenTelemetry Java agent at startup, exactly as a
customer would.

| Endpoint | What it produces in PulseOps |
|---|---|
| `GET /api/orders/{id}` | fast reads; IDs above 50 return 404 |
| `GET /api/orders?status=&limit=` | JPA list queries |
| `POST /api/orders` | HTTP call to `/internal/inventory/{sku}` (trace with a CLIENT → SERVER hop), payment check, INSERT. `LP-404` is declined (402, WARN log); `XX-999` is unknown (422) |
| `GET /api/orders/report` | aggregate query; the one made slow by the `slow-db` fault |
| `GET/PUT /admin/chaos` | fault injection (below) |

A built-in traffic generator sends ~2 requests/second (`SAMPLE_TRAFFIC_RPS`, `0` = off),
so data keeps flowing without a load tool.

## Run it against a local PulseOps (Windows / PowerShell)

Prerequisites: the Docker PostgreSQL container from `docs/DEVELOPMENT.md` (`pulseops-pg`
on port 5433) and IntelliJ with JDK 21+.

**1. Create a database for the running backend** (the test database stays separate):

```powershell
docker exec -it pulseops-pg psql -U postgres -c "CREATE DATABASE pulseops_dev OWNER pulseops_app;"
```

**2. Start the PulseOps backend.** In IntelliJ, run `PulseOpsApplication` with these
environment variables (Run → Edit Configurations → Environment variables):

```
SPRING_PROFILES_ACTIVE=dev
PULSEOPS_DB_URL=jdbc:postgresql://localhost:5433/pulseops_dev
PULSEOPS_DB_USER=pulseops_app
PULSEOPS_DB_PASSWORD=dev_only_password
PULSEOPS_JWT_SECRET=<generate below>
PULSEOPS_REQUIRE_EMAIL_VERIFICATION=false
PULSEOPS_COOKIE_SECURE=false
```

Generate a JWT secret once in PowerShell:

```powershell
$b = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

Wait for `Started PulseOpsApplication` in the log (Flyway creates the schema on first start).

**3. Create the demo account, service and key** (in a PowerShell window at `F:\PULSEOPS`):

```powershell
Set-ExecutionPolicy -Scope Process Bypass     # allow local scripts in this window only
./scripts/dev-bootstrap.ps1
$env:PULSEOPS_INGEST_KEY = '<the po_... key it printed>'
```

**4. Build the sample once.** In IntelliJ: right-click `examples/order-service/pom.xml` →
*Add as Maven Project*, then Maven panel → *order-service* → Lifecycle → **package**. This
also runs the sample's own tests.

**5. Run it with the agent** (same PowerShell window):

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\openjdk-25.0.1"   # or any JDK 21+
./examples/order-service/run-with-agent.ps1
```

The script downloads the pinned OpenTelemetry Java agent into `examples/order-service/agent/`
(git-ignored) on first use and starts the app on `http://127.0.0.1:8081`.

**6. Check that PulseOps receives it** (another PowerShell window):

```powershell
./scripts/dev-status.ps1      # status should become CONNECTED within ~10 seconds
```

See what the monitoring APIs report (health, request rate, error rate, p50/p95/p99 per
endpoint, JVM CPU/heap):

```powershell
./scripts/dev-monitoring.ps1                 # add -Sort p95 or -Minutes 15 as you like
```

You can also look at the stored data directly.
The `postgres` superuser bypasses row-level security, so use it only for this local check:

```powershell
docker exec -it pulseops-pg psql -U postgres -d pulseops_dev -c "SELECT route_template, sum(request_count) AS requests, sum(error_count) AS errors FROM api_metrics GROUP BY 1 ORDER BY 2 DESC;"
docker exec -it pulseops-pg psql -U postgres -d pulseops_dev -c "SELECT kind, status_code, count(*) FROM distributed_traces GROUP BY 1,2;"
docker exec -it pulseops-pg psql -U postgres -d pulseops_dev -c "SELECT operation_type, left(operation, 80) AS query, count(*), round(avg(duration_ms)::numeric, 2) AS avg_ms FROM database_operations GROUP BY 1,2 ORDER BY 3 DESC LIMIT 10;"
docker exec -it pulseops-pg psql -U postgres -d pulseops_dev -c "SELECT level, count(*) FROM application_logs GROUP BY 1;"
docker exec -it pulseops-pg psql -U postgres -d pulseops_dev -c "SELECT name, count(*) FROM metric_points GROUP BY 1 ORDER BY 1 LIMIT 15;"
```

## Inject faults

```powershell
Invoke-RestMethod -Method Put "http://127.0.0.1:8081/admin/chaos?preset=slow-db"   # 1.5 s report/read queries
Invoke-RestMethod -Method Put "http://127.0.0.1:8081/admin/chaos?preset=errors"    # 50% inventory failures -> 503s + ERROR logs
Invoke-RestMethod -Method Put "http://127.0.0.1:8081/admin/chaos?preset=latency"   # +800 ms on every request
Invoke-RestMethod -Method Put "http://127.0.0.1:8081/admin/chaos?preset=outage"    # all of the above, harder
Invoke-RestMethod -Method Put "http://127.0.0.1:8081/admin/chaos?preset=normal"
```

These are the scenarios the anomaly detection, correlation and root-cause phases are
validated against. `/admin/chaos` is unauthenticated, which is why the service only
listens on 127.0.0.1 (`SAMPLE_BIND_ADDRESS` changes that — don't, outside a lab).

## Linux / macOS

`scripts/dev-bootstrap.sh` (needs `curl` and `python3`) and
`examples/order-service/run-with-agent.sh` do the same as the PowerShell scripts.

## What the agent sends

`OTEL_SERVICE_NAME=order-service`, `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`,
`OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:8080/otlp`,
`OTEL_EXPORTER_OTLP_HEADERS=x-pulseops-key=…`, `OTEL_EXPORTER_OTLP_COMPRESSION=gzip`,
and `OTEL_METRIC_EXPORT_INTERVAL=15000` (demo only; the default is 60 s). Spans are
exported every few seconds, logs continuously and metrics every 15 s. The key is
service-scoped, so `service.name` cannot redirect data to another service.
