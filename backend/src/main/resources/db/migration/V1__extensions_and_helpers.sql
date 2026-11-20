-- PulseOps V1: shared helpers.
-- UUIDs use gen_random_uuid() (built into PostgreSQL 13+; no extension needed).

-- Maintains updated_at on every UPDATE for tables that attach this trigger.
CREATE FUNCTION pulseops_set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

-- Resolves the tenant bound to the current transaction (set by the backend via
-- SET LOCAL app.current_org_id = '<uuid>'). Returns NULL when unset, so RLS
-- policies comparing against it FAIL CLOSED (no rows) outside a tenant context.
CREATE FUNCTION pulseops_current_org_id() RETURNS uuid
LANGUAGE sql STABLE AS $$
    SELECT NULLIF(current_setting('app.current_org_id', true), '')::uuid
$$;
