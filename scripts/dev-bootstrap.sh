#!/usr/bin/env bash
# DEVELOPMENT ONLY: same as dev-bootstrap.ps1 for Linux/macOS (needs curl and python3).
# Creates/reuses a demo account, organization, project and service in a local PulseOps
# backend, selects the Java-agent integration and prints a new service-scoped key.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${DEMO_EMAIL:-demo@example.com}"
PASSWORD="${DEMO_PASSWORD:-pulseops-local-demo-only}"
ORG_NAME="${ORG_NAME:-PulseOps Demo}"
PROJECT_NAME="${PROJECT_NAME:-Shop}"
SERVICE_NAME="${SERVICE_NAME:-order-service}"
TOKEN=""

# api METHOD PATH [JSON]  -> prints body; exits on HTTP >= 400 unless ALLOW_STATUS matches.
api() {
  local method=$1 path=$2 body=${3:-} out status
  out=$(mktemp)
  local args=(-sS -o "$out" -w '%{http_code}' -X "$method" "$BASE_URL$path" -H 'Content-Type: application/json')
  [ -n "$TOKEN" ] && args+=(-H "Authorization: Bearer $TOKEN")
  [ -n "$body" ] && args+=(--data "$body")
  status=$(curl "${args[@]}") || { rm -f "$out"; echo "PulseOps backend not reachable at $BASE_URL" >&2; exit 1; }
  if [ "$status" -ge 400 ] && [ "$status" != "${ALLOW_STATUS:-}" ]; then
    echo "$method $path -> HTTP $status: $(cat "$out")" >&2; rm -f "$out"; exit 1
  fi
  cat "$out"; rm -f "$out"
}
# json EXPR < body   (EXPR is Python over `d`)
json() { python3 -c "import json,sys; d=json.load(sys.stdin); v=$1; print('' if v is None else v)" 2>/dev/null; }
jstr() { python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$1"; }
find_id() { python3 -c "import json,sys; items=json.load(sys.stdin); items=items.get('content',items) if isinstance(items,dict) else items; print(next((i['id'] for i in items if i['name']==sys.argv[1]),''))" "$1"; }

api GET /actuator/health >/dev/null

ALLOW_STATUS=409 api POST /api/v1/auth/register \
  "{\"email\":$(jstr "$EMAIL"),\"password\":$(jstr "$PASSWORD"),\"fullName\":\"Demo User\"}" >/dev/null
TOKEN=$(api POST /api/v1/auth/login "{\"email\":$(jstr "$EMAIL"),\"password\":$(jstr "$PASSWORD")}" | json "d['accessToken']")

ORG_ID=$(api GET /api/v1/organizations | find_id "$ORG_NAME")
[ -z "$ORG_ID" ] && ORG_ID=$(api POST /api/v1/organizations "{\"name\":$(jstr "$ORG_NAME")}" | json "d['id']")
ORG="/api/v1/organizations/$ORG_ID"

PROJECT_ID=$(api GET "$ORG/projects?size=100" | find_id "$PROJECT_NAME")
[ -z "$PROJECT_ID" ] && PROJECT_ID=$(api POST "$ORG/projects" \
  "{\"name\":$(jstr "$PROJECT_NAME"),\"environment\":\"development\",\"techStack\":\"Spring Boot\"}" | json "d['id']")

SERVICE_ID=$(api GET "$ORG/projects/$PROJECT_ID/services?size=100" | find_id "$SERVICE_NAME")
[ -z "$SERVICE_ID" ] && SERVICE_ID=$(api POST "$ORG/projects/$PROJECT_ID/services" \
  "{\"name\":$(jstr "$SERVICE_NAME"),\"technology\":\"java_spring_boot\"}" | json "d['id']")

api PUT "$ORG/services/$SERVICE_ID/integration" '{"type":"JAVA_AGENT"}' >/dev/null
KEY=$(api POST "$ORG/projects/$PROJECT_ID/credentials" \
  "{\"name\":$(jstr "$SERVICE_NAME agent $(date '+%Y-%m-%d %H:%M:%S')"),\"serviceId\":\"$SERVICE_ID\"}" | json "d['key']")

cat <<MSG

Integration key (shown once - store it securely):
  $KEY

Next:
  export PULSEOPS_INGEST_KEY='$KEY'
  ./examples/order-service/run-with-agent.sh

Connection status:
  curl -s -H "Authorization: Bearer <token>" $BASE_URL$ORG/services/$SERVICE_ID/integration
MSG
