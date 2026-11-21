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
for f in "$MIGRATIONS_DIR"/V*.sql; do
  echo "    $(basename "$f")"
  PGPASSWORD="$APP_PASSWORD" psql -h "$PGHOST" -U "$APP_ROLE" -d "$DB" -v ON_ERROR_STOP=1 -q -f "$f"
done

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

-- Seed catalog present.
DO $$ BEGIN
  IF (SELECT count(*) FROM permissions) < 11 THEN RAISE EXCEPTION 'permissions seed missing'; END IF;
  IF (SELECT count(*) FROM roles WHERE is_system) <> 4 THEN RAISE EXCEPTION 'system roles seed missing'; END IF;
END $$;
SQL

echo "==> OK: migrations apply cleanly and tenant isolation holds."
