#!/usr/bin/env bash
# Runs the sample order service with the OpenTelemetry Java agent, exporting to PulseOps.
#   export PULSEOPS_INGEST_KEY=po_...     # from scripts/dev-bootstrap (see README)
#   ./examples/order-service/run-with-agent.sh
set -euo pipefail

: "${PULSEOPS_INGEST_KEY:?Set PULSEOPS_INGEST_KEY to the integration key}"
OTLP_ENDPOINT="${PULSEOPS_OTLP_ENDPOINT:-http://localhost:8080/otlp}"
SERVICE_NAME="${SERVICE_NAME:-order-service}"
AGENT_VERSION="${AGENT_VERSION:-2.32.0}"   # pinned for reproducible demos

HERE="$(cd "$(dirname "$0")" && pwd)"
AGENT="$HERE/agent/opentelemetry-javaagent-$AGENT_VERSION.jar"
if [ ! -f "$AGENT" ]; then
  mkdir -p "$HERE/agent"
  echo "Downloading OpenTelemetry Java agent $AGENT_VERSION from GitHub releases..."
  curl -fsSL -o "$AGENT.part" \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v$AGENT_VERSION/opentelemetry-javaagent.jar"
  mv "$AGENT.part" "$AGENT"
fi

JAR="$HERE/target/order-service.jar"
if [ ! -f "$JAR" ]; then
  if command -v mvn >/dev/null; then
    (cd "$HERE" && mvn -q -DskipTests package)
  else
    echo "Build the sample first (mvn package, or Maven 'package' in your IDE)." >&2
    exit 1
  fi
fi

export OTEL_SERVICE_NAME="$SERVICE_NAME"
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
export OTEL_EXPORTER_OTLP_ENDPOINT="$OTLP_ENDPOINT"
export OTEL_EXPORTER_OTLP_HEADERS="x-pulseops-key=$PULSEOPS_INGEST_KEY"
export OTEL_EXPORTER_OTLP_COMPRESSION=gzip
export OTEL_RESOURCE_ATTRIBUTES=deployment.environment.name=development
export OTEL_METRIC_EXPORT_INTERVAL=15000
export SAMPLE_TRAFFIC_RPS="${SAMPLE_TRAFFIC_RPS:-2}"

echo "Starting $SERVICE_NAME on http://127.0.0.1:8081 -> exporting to $OTLP_ENDPOINT (Ctrl+C to stop)"
exec java "-javaagent:$AGENT" -jar "$JAR"
