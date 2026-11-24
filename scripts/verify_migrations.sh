#!/usr/bin/env bash
# PulseOps — apply all migrations to a scratch database and assert schema invariants,
# including Row-Level Security tenant isolation. Safe to re-run; uses pulseops_test only.
#
# Usage: PGHOST=localhost PGSUPERUSER=postgres ./scripts/verify_migrations.sh
# Requires: psql, a running PostgreSQL 15/16, superuser access to (re)create the scratch DB.
set -euo pipefail

PGHOST="${PGHOST:-localhost}"
PGSUPERUSER="${PGSUPERUSER:-postgres}"
APP_ROLE=pulseops_app
APP_PASSWORD="${PULSEOPS_TEST_DB_PASSWORD:-dev_only_password}"
DB=pulseops_test
MIGRATIONS_DIR="$(cd "$(dirname "$0")/../backend/src/main/resources/db/migration" && pwd)"

echo "==> Recreating scratch database $DB"
psql -h "$PGHOST" -U "$PGSUPERUSER" -v ON_ERROR_STOP=1 -q <<SQL
DROP DATABASE IF EXISTS $DB;
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$APP_ROLE') THEN
    CREATE ROLE $APP_ROLE LOGIN PASSWORD '$APP_PASSWORD';
  END IF;
END \$\$;
CREATE DATABASE $DB OWNER $APP_ROLE;
SQL

echo "==> Applying migrations from $MIGRATIONS_DIR as $APP_ROLE (non-superuser, like Flyway)"
# Numeric version order, as Flyway applies them (a plain glob would put V10 before V2).
while IFS= read -r name; do
  echo "    $name"
  PGPASSWORD="$APP_PASSWORD" psql -h "$PGHOST" -U "$APP_ROLE" -d "$DB" -v ON_ERROR_STOP=1 -q -f "$MIGRATIONS_DIR/$name"
done < <(cd "$MIGRATIONS_DIR" && ls V*__*.sql | sort -V)

echo "==> Asserting invariants"
PGPASSWORD="$APP_PASSWORD" psql -h "$PGHOST" -U "$APP_ROLE" -d "$DB" -v ON_ERROR_STOP=1 -q <<'SQL'
-- Fixtures (no tenant context: org INSERT is allowed, SELECT is hidden).
INSERT INTO users (id,email,password_hash,full_name,status) VALUES
 ('10000000-0000-0000-0000-000000000001','alice@acme.test','x','Alice','active');
INSERT INTO organizations (id,name,slug,created_by) VALUES
 ('20000000-0000-0000-0000-00000000000a','Acme','acme','10000000-0000-0000-0000-000000000001'),
 ('20000000-0000-0000-0000-00000000000b','Globex','globex','10000000-0000-0000-0000-000000000001');

DO $$ BEGIN
  IF (SELECT count(*) FROM organizations) <> 0 THEN
    RAISE EXCEPTION 'RLS breach: organizations visible without tenant context';
  END IF;
END $$;

-- Tenant A inserts and sees its own project.
BEGIN;
SET LOCAL app.current_org_id = '20000000-0000-0000-0000-00000000000a';
INSERT INTO projects (id,organization_id,name)
 VALUES ('30000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-00000000000a','P1');
DO $$ BEGIN
  IF (SELECT count(*) FROM projects) <> 1 THEN RAISE EXCEPTION 'tenant cannot see own project'; END IF;
  IF (SELECT count(*) FROM organizations) <> 1 THEN RAISE EXCEPTION 'tenant org row not visible'; END IF;
END $$;
COMMIT;

-- Cross-tenant write must be rejected.
DO $$ BEGIN
  BEGIN
    SET LOCAL app.current_org_id = '20000000-0000-0000-0000-00000000000a';
    INSERT INTO projects (organization_id,name) VALUES ('20000000-0000-0000-0000-00000000000b','evil');
    RAISE EXCEPTION 'RLS breach: cross-tenant write was ALLOWED';
  EXCEPTION WHEN insufficient_privilege OR SQLSTATE '42501' THEN
    NULL; -- expected
  END;
END $$;

-- Tenant B sees nothing of A; no context sees nothing.
BEGIN;
SET LOCAL app.current_org_id = '20000000-0000-0000-0000-00000000000b';
DO $$ BEGIN
  IF (SELECT count(*) FROM projects) <> 0 THEN RAISE EXCEPTION 'RLS breach: cross-tenant read'; END IF;
END $$;
COMMIT;
DO $$ BEGIN
  IF (SELECT count(*) FROM projects) <> 0 THEN RAISE EXCEPTION 'RLS breach: read without context'; END IF;
END $$;

-- V9 user-scoped visibility: with only a user context, a user sees their own memberships
-- and organizations, nothing else, and cannot write into another organization.
INSERT INTO users (id,email,password_hash,full_name,status) VALUES
 ('10000000-0000-0000-0000-000000000002','bob@globex.test','x','Bob','active');
BEGIN;
SET LOCAL app.current_org_id = '20000000-0000-0000-0000-00000000000a';
INSERT INTO organization_members (organization_id,user_id,role_id) VALUES
 ('20000000-0000-0000-0000-00000000000a','10000000-0000-0000-0000-000000000001','b0000000-0000-0000-0000-000000000001');
COMMIT;
BEGIN;
SET LOCAL app.current_org_id = '20000000-0000-0000-0000-00000000000b';
INSERT INTO organization_members (organization_id,user_id,role_id) VALUES
 ('20000000-0000-0000-0000-00000000000b','10000000-0000-0000-0000-000000000002','b0000000-0000-0000-0000-000000000001');
COMMIT;

BEGIN;
SET LOCAL app.current_user_id = '10000000-0000-0000-0000-000000000001';
DO $$ BEGIN
  IF (SELECT count(*) FROM organization_members) <> 1 THEN RAISE EXCEPTION 'user sees foreign memberships'; END IF;
  IF (SELECT string_agg(slug, ',') FROM organizations) IS DISTINCT FROM 'acme' THEN
    RAISE EXCEPTION 'user org visibility wrong';
  END IF;
  IF (SELECT count(*) FROM projects) <> 0 THEN RAISE EXCEPTION 'user context exposed tenant data'; END IF;
END $$;
DO $$ BEGIN
  BEGIN
    INSERT INTO organization_members (organization_id,user_id,role_id) VALUES
     ('20000000-0000-0000-0000-00000000000b','10000000-0000-0000-0000-000000000001','b0000000-0000-0000-0000-000000000001');
    RAISE EXCEPTION 'RLS breach: user context allowed joining another organization';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;
COMMIT;

-- A removed member loses organization visibility.
BEGIN;
SET LOCAL app.current_org_id = '20000000-0000-0000-0000-00000000000a';
UPDATE organization_members SET status = 'removed' WHERE user_id = '10000000-0000-0000-0000-000000000001';
COMMIT;
BEGIN;
SET LOCAL app.current_user_id = '10000000-0000-0000-0000-000000000001';
DO $$ BEGIN
  IF (SELECT count(*) FROM organizations) <> 0 THEN RAISE EXCEPTION 'removed member still sees organization'; END IF;
END $$;
COMMIT;

-- V10 credential-by-secret: a key's row is visible only to a transaction presenting its hash.
BEGIN;
SET LOCAL app.current_org_id = '20000000-0000-0000-0000-00000000000a';
INSERT INTO integration_credentials (organization_id, project_id, name, key_prefix, secret_hash)
 VALUES ('20000000-0000-0000-0000-00000000000a','30000000-0000-0000-0000-000000000001','k1','po_x','HASH_A'),
        ('20000000-0000-0000-0000-00000000000a','30000000-0000-0000-0000-000000000001','k2','po_y','HASH_B');
COMMIT;
DO $$ BEGIN
  IF (SELECT count(*) FROM integration_credentials) <> 0 THEN RAISE EXCEPTION 'credentials visible without context'; END IF;
END $$;
BEGIN;
SELECT set_config('app.credential_hash', 'HASH_A', true);
DO $$ BEGIN
  IF (SELECT string_agg(name, ',') FROM integration_credentials) IS DISTINCT FROM 'k1' THEN
    RAISE EXCEPTION 'credential_by_secret exposed wrong rows';
  END IF;
END $$;
UPDATE integration_credentials SET last_used_at = now();
DO $$ BEGIN
  IF (SELECT count(*) FROM integration_credentials WHERE last_used_at IS NOT NULL) <> 0 THEN
    RAISE EXCEPTION 'hash context was able to write';
  END IF;
END $$;
COMMIT;
DO $$ BEGIN
  IF (SELECT count(*) FROM integration_credentials) <> 0 THEN RAISE EXCEPTION 'credential hash setting leaked past commit'; END IF;
  IF pulseops_histogram_add('{1,2,3}', '{10,20}') <> '{11,22,3}'::bigint[] THEN RAISE EXCEPTION 'histogram add wrong'; END IF;
END $$;

-- V11 OTLP: span IDs are unique per tenant (another tenant reusing an ID must not be blocked),
-- a retried span is ignored, metric aggregates merge, and metric_points is tenant-isolated.
BEGIN;
SET LOCAL app.current_org_id = '20000000-0000-0000-0000-00000000000b';
INSERT INTO projects (id,organization_id,name)
 VALUES ('30000000-0000-0000-0000-000000000002','20000000-0000-0000-0000-00000000000b','PB');
INSERT INTO services (id,organization_id,project_id,name)
 VALUES ('40000000-0000-0000-0000-00000000000b','20000000-0000-0000-0000-00000000000b','30000000-0000-0000-0000-000000000002','svc-b');
INSERT INTO distributed_traces (organization_id,service_id,trace_id,span_id,name,start_time,duration_ms)
 VALUES ('20000000-0000-0000-0000-00000000000b','40000000-0000-0000-0000-00000000000b',
         '4bf92f3577b34da6a3ce929d0e0e4736','00f067aa0ba902b7','b-span',now(),1);
COMMIT;
BEGIN;
SET LOCAL app.current_org_id = '20000000-0000-0000-0000-00000000000a';
INSERT INTO services (id,organization_id,project_id,name)
 VALUES ('40000000-0000-0000-0000-00000000000a','20000000-0000-0000-0000-00000000000a','30000000-0000-0000-0000-000000000001','svc-a');
INSERT INTO distributed_traces (organization_id,service_id,trace_id,span_id,name,start_time,duration_ms)
 VALUES ('20000000-0000-0000-0000-00000000000a','40000000-0000-0000-0000-00000000000a',
         '4bf92f3577b34da6a3ce929d0e0e4736','00f067aa0ba902b7','a-span',now(),1)
 ON CONFLICT (organization_id, trace_id, span_id) DO NOTHING;
INSERT INTO distributed_traces (organization_id,service_id,trace_id,span_id,name,start_time,duration_ms)
 VALUES ('20000000-0000-0000-0000-00000000000a','40000000-0000-0000-0000-00000000000a',
         '4bf92f3577b34da6a3ce929d0e0e4736','00f067aa0ba902b7','a-retry',now(),1)
 ON CONFLICT (organization_id, trace_id, span_id) DO NOTHING;
DO $$ BEGIN
  IF (SELECT string_agg(name, ',') FROM distributed_traces) IS DISTINCT FROM 'a-span' THEN
    RAISE EXCEPTION 'span uniqueness wrong: expected only tenant A''s first span';
  END IF;
END $$;
INSERT INTO metric_points (organization_id,service_id,name,unit,kind,attributes,attributes_key,bucket_start,
                           sample_count,value_sum,value_min,value_max,value_last)
 VALUES ('20000000-0000-0000-0000-00000000000a','40000000-0000-0000-0000-00000000000a','jvm.memory.used','By',
         'gauge','{"jvm.memory.type":"heap"}','jvm.memory.type=heap','2026-11-21 10:00:00+00',2,300,100,200,200);
INSERT INTO metric_points (organization_id,service_id,name,unit,kind,attributes,attributes_key,bucket_start,
                           sample_count,value_sum,value_min,value_max,value_last)
 VALUES ('20000000-0000-0000-0000-00000000000a','40000000-0000-0000-0000-00000000000a','jvm.memory.used','By',
         'gauge','{"jvm.memory.type":"heap"}','jvm.memory.type=heap','2026-11-21 10:00:00+00',1,50,50,50,NULL)
 ON CONFLICT (service_id, name, attributes_key, bucket_start) DO UPDATE SET
   sample_count = metric_points.sample_count + EXCLUDED.sample_count,
   value_sum    = metric_points.value_sum + EXCLUDED.value_sum,
   value_min    = LEAST(metric_points.value_min, EXCLUDED.value_min),
   value_max    = GREATEST(metric_points.value_max, EXCLUDED.value_max),
   value_last   = COALESCE(EXCLUDED.value_last, metric_points.value_last);
DO $$ BEGIN
  IF (SELECT format('%s/%s/%s/%s/%s', sample_count, value_sum, value_min, value_max, value_last) FROM metric_points)
     IS DISTINCT FROM '3/350/50/200/200' THEN
    RAISE EXCEPTION 'metric merge wrong: %', (SELECT format('%s/%s/%s/%s/%s', sample_count, value_sum, value_min, value_max, value_last) FROM metric_points);
  END IF;
END $$;
COMMIT;
BEGIN;
SET LOCAL app.current_org_id = '20000000-0000-0000-0000-00000000000b';
DO $$ BEGIN
  IF (SELECT count(*) FROM metric_points) <> 0 THEN RAISE EXCEPTION 'RLS breach: metric_points cross-tenant'; END IF;
  IF (SELECT string_agg(name, ',') FROM distributed_traces) IS DISTINCT FROM 'b-span' THEN
    RAISE EXCEPTION 'tenant B span missing or foreign span visible';
  END IF;
END $$;
COMMIT;

-- V12: discovery policy reveals connections across tenants only with app.discovery='on',
-- read-only; one active anomaly per (service, metric, scope) with upsert merging.
BEGIN;
SET LOCAL app.current_org_id = '20000000-0000-0000-0000-00000000000a';
INSERT INTO integration_connections (organization_id, service_id, integration_type, status, last_telemetry_at)
 VALUES ('20000000-0000-0000-0000-00000000000a','40000000-0000-0000-0000-00000000000a','otlp','connected',now());
COMMIT;
BEGIN;
SET LOCAL app.current_org_id = '20000000-0000-0000-0000-00000000000b';
INSERT INTO integration_connections (organization_id, service_id, integration_type, status, last_telemetry_at)
 VALUES ('20000000-0000-0000-0000-00000000000b','40000000-0000-0000-0000-00000000000b','otlp','connected',now());
COMMIT;
BEGIN;
DO $$ BEGIN
  IF (SELECT count(*) FROM integration_connections) <> 0 THEN RAISE EXCEPTION 'connections visible without context'; END IF;
END $$;
SELECT set_config('app.discovery', 'on', true);
DO $$ BEGIN
  IF (SELECT count(*) FROM integration_connections) <> 2 THEN RAISE EXCEPTION 'discovery should see both tenants'; END IF;
  BEGIN
    UPDATE integration_connections SET status = 'disabled';
    IF FOUND THEN RAISE EXCEPTION 'discovery must not allow writes'; END IF;
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;
COMMIT;
BEGIN;
DO $$ BEGIN
  IF (SELECT count(*) FROM integration_connections) <> 0 THEN RAISE EXCEPTION 'discovery setting leaked past commit'; END IF;
END $$;
COMMIT;
BEGIN;
SET LOCAL app.current_org_id = '20000000-0000-0000-0000-00000000000a';
INSERT INTO anomalies (organization_id, project_id, service_id, metric, scope, anomaly_type, severity, observed_value,
                       expected_value, score, window_start, window_end, last_seen_at)
 VALUES ('20000000-0000-0000-0000-00000000000a','30000000-0000-0000-0000-000000000001','40000000-0000-0000-0000-00000000000a',
         'response_latency','service','spike','medium',300,12,40,now()-interval '5 min',now(),now());
INSERT INTO anomalies (organization_id, project_id, service_id, metric, scope, anomaly_type, severity, observed_value,
                       expected_value, score, window_start, window_end, last_seen_at)
 VALUES ('20000000-0000-0000-0000-00000000000a','30000000-0000-0000-0000-000000000001','40000000-0000-0000-0000-00000000000a',
         'response_latency','service','spike','critical',1500,12,95,now()-interval '4 min',now()+interval '1 min',now())
 ON CONFLICT (service_id, metric, scope) WHERE status IN ('open', 'linked') DO UPDATE SET
   severity = CASE WHEN array_position(ARRAY['low','medium','high','critical'], EXCLUDED.severity)
                      > array_position(ARRAY['low','medium','high','critical'], anomalies.severity)
                   THEN EXCLUDED.severity ELSE anomalies.severity END,
   observed_value = EXCLUDED.observed_value, score = GREATEST(anomalies.score, EXCLUDED.score),
   window_end = GREATEST(anomalies.window_end, EXCLUDED.window_end),
   last_seen_at = EXCLUDED.last_seen_at, occurrences = anomalies.occurrences + 1;
DO $$ BEGIN
  IF (SELECT format('%s/%s/%s/%s', count(*), max(severity), max(occurrences), max(score)) FROM anomalies)
     IS DISTINCT FROM '1/critical/2/95' THEN
    RAISE EXCEPTION 'anomaly upsert wrong: %', (SELECT format('%s/%s/%s/%s', count(*), max(severity), max(occurrences), max(score)) FROM anomalies);
  END IF;
END $$;
UPDATE anomalies SET status = 'resolved', resolved_at = now();
INSERT INTO anomalies (organization_id, project_id, service_id, metric, scope, anomaly_type, severity, observed_value,
                       score, window_start, window_end)
 VALUES ('20000000-0000-0000-0000-00000000000a','30000000-0000-0000-0000-000000000001','40000000-0000-0000-0000-00000000000a',
         'response_latency','service','spike','low',200,5,now(),now());
DO $$ BEGIN
  IF (SELECT count(*) FROM anomalies) <> 2 THEN RAISE EXCEPTION 'a resolved anomaly must not block a new one'; END IF;
END $$;
COMMIT;

-- V13: one active automatic incident per dedup key; correlation event types accepted.
BEGIN;
SET LOCAL app.current_org_id = '20000000-0000-0000-0000-00000000000a';
INSERT INTO incidents (organization_id, project_id, title, severity, status, source, dedup_key)
 VALUES ('20000000-0000-0000-0000-00000000000a','30000000-0000-0000-0000-000000000001','Latency increase on svc-a',
         'high','open','automatic','auto:project:30000000-0000-0000-0000-000000000001');
INSERT INTO incidents (organization_id, project_id, title, severity, status, source, dedup_key)
 VALUES ('20000000-0000-0000-0000-00000000000a','30000000-0000-0000-0000-000000000001','duplicate',
         'high','open','automatic','auto:project:30000000-0000-0000-0000-000000000001')
 ON CONFLICT (organization_id, dedup_key) WHERE dedup_key IS NOT NULL AND status NOT IN ('resolved', 'closed') DO NOTHING;
INSERT INTO incident_events (organization_id, incident_id, event_type, details)
 SELECT organization_id, id, 'anomaly_linked', '{}' FROM incidents;
INSERT INTO incident_events (organization_id, incident_id, event_type, details)
 SELECT organization_id, id, 'anomalies_recovered', '{}' FROM incidents;
DO $$ BEGIN
  IF (SELECT count(*) FROM incidents) <> 1 THEN RAISE EXCEPTION 'automatic incident dedup failed'; END IF;
  BEGIN
    INSERT INTO incident_events (organization_id, incident_id, event_type, details)
     SELECT organization_id, id, 'exploded', '{}' FROM incidents;
    RAISE EXCEPTION 'unknown incident event type accepted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
END $$;
UPDATE incidents SET status = 'resolved', resolved_at = now();
INSERT INTO incidents (organization_id, project_id, title, severity, status, source, dedup_key)
 VALUES ('20000000-0000-0000-0000-00000000000a','30000000-0000-0000-0000-000000000001','Again',
         'medium','open','automatic','auto:project:30000000-0000-0000-0000-000000000001');
COMMIT;
BEGIN;
SET LOCAL app.current_org_id = '20000000-0000-0000-0000-00000000000b';
DO $$ BEGIN
  IF (SELECT count(*) FROM incidents) + (SELECT count(*) FROM incident_events) <> 0 THEN
    RAISE EXCEPTION 'RLS breach: incidents cross-tenant';
  END IF;
END $$;
COMMIT;

-- Seed catalog present.
DO $$ BEGIN
  IF (SELECT count(*) FROM permissions) < 11 THEN RAISE EXCEPTION 'permissions seed missing'; END IF;
  IF (SELECT count(*) FROM roles WHERE is_system) <> 4 THEN RAISE EXCEPTION 'system roles seed missing'; END IF;
END $$;
SQL

echo "==> OK: migrations apply cleanly and tenant isolation holds."
