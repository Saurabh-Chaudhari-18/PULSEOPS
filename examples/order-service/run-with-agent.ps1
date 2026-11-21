<#
.SYNOPSIS
  Runs the sample order service with the OpenTelemetry Java agent attached, exporting
  traces, metrics and logs to PulseOps over OTLP/HTTP. No code changes in the app.

.EXAMPLE
  $env:PULSEOPS_INGEST_KEY = 'po_...'      # from scripts/dev-bootstrap.ps1
  ./examples/order-service/run-with-agent.ps1
#>
param(
    [string]$IngestKey = $env:PULSEOPS_INGEST_KEY,
    [string]$OtlpEndpoint = $(if ($env:PULSEOPS_OTLP_ENDPOINT) { $env:PULSEOPS_OTLP_ENDPOINT } else { "http://localhost:8080/otlp" }),
    [string]$ServiceName = "order-service",
    # Pinned for reproducible demos; pass -AgentVersion to use another release.
    [string]$AgentVersion = "2.32.0",
    [int]$TrafficPerSecond = 2
)
$ErrorActionPreference = "Stop"
if (-not $IngestKey) { throw "Set `$env:PULSEOPS_INGEST_KEY (printed by scripts/dev-bootstrap.ps1) or pass -IngestKey." }

$here = $PSScriptRoot
$agentDir = Join-Path $here "agent"
$agent = Join-Path $agentDir "opentelemetry-javaagent-$AgentVersion.jar"
if (-not (Test-Path $agent)) {
    New-Item -ItemType Directory -Force -Path $agentDir | Out-Null
    $url = "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v$AgentVersion/opentelemetry-javaagent.jar"
    Write-Host "Downloading OpenTelemetry Java agent $AgentVersion from GitHub releases..."
    Invoke-WebRequest -Uri $url -OutFile "$agent.part" -UseBasicParsing
    Move-Item "$agent.part" $agent
}

$jar = Join-Path $here "target\order-service.jar"
if (-not (Test-Path $jar)) {
    if (Get-Command mvn -ErrorAction SilentlyContinue) {
        Push-Location $here
        try { mvn -q -DskipTests package } finally { Pop-Location }
    } else {
        throw "Build the sample first: in IntelliJ open examples/order-service/pom.xml as a Maven project and run Lifecycle > package (or install Maven)."
    }
}

# Standard OpenTelemetry configuration — the same values the PulseOps installation guide shows.
$env:OTEL_SERVICE_NAME = $ServiceName
$env:OTEL_EXPORTER_OTLP_PROTOCOL = "http/protobuf"
$env:OTEL_EXPORTER_OTLP_ENDPOINT = $OtlpEndpoint
$env:OTEL_EXPORTER_OTLP_HEADERS = "x-pulseops-key=$IngestKey"
$env:OTEL_EXPORTER_OTLP_COMPRESSION = "gzip"
$env:OTEL_RESOURCE_ATTRIBUTES = "deployment.environment.name=development"
# Demo-friendly: metrics every 15 s instead of the default 60 s.
$env:OTEL_METRIC_EXPORT_INTERVAL = "15000"
$env:SAMPLE_TRAFFIC_RPS = "$TrafficPerSecond"

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
Write-Host "Starting $ServiceName on http://127.0.0.1:8081 -> exporting to $OtlpEndpoint (Ctrl+C to stop)"
& $java "-javaagent:$agent" -jar $jar
