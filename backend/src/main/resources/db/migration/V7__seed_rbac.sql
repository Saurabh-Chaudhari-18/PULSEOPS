-- PulseOps V7: seed built-in RBAC catalog (system roles + permissions).
-- Runs BEFORE RLS is enabled (V8): system roles have organization_id NULL and
-- could not pass tenant WITH CHECK policies. Future seed migrations that touch
-- RLS-protected tables must account for this (see docs/DATABASE.md).
-- Fixed UUIDs keep seeds deterministic across environments.

INSERT INTO permissions (id, code, description) VALUES
    ('a0000000-0000-0000-0000-000000000001', 'org.manage',          'Change organization settings; delete organization'),
    ('a0000000-0000-0000-0000-000000000002', 'org.members.manage',  'Invite, remove and change roles of members'),
    ('a0000000-0000-0000-0000-000000000003', 'project.read',        'View projects and services'),
    ('a0000000-0000-0000-0000-000000000004', 'project.manage',      'Create, edit, archive projects and services'),
    ('a0000000-0000-0000-0000-000000000005', 'credential.manage',   'Create, rotate and revoke integration credentials'),
    ('a0000000-0000-0000-0000-000000000006', 'telemetry.read',      'View metrics, logs, traces, database operations'),
    ('a0000000-0000-0000-0000-000000000007', 'incident.read',       'View anomalies and incidents'),
    ('a0000000-0000-0000-0000-000000000008', 'incident.manage',     'Create, assign, transition, comment on incidents'),
    ('a0000000-0000-0000-0000-000000000009', 'alert.manage',        'Create and edit alert rules'),
    ('a0000000-0000-0000-0000-00000000000a', 'analytics.read',      'View analytics dashboards'),
    ('a0000000-0000-0000-0000-00000000000b', 'audit.read',          'View the audit log');

INSERT INTO roles (id, organization_id, name, description, is_system) VALUES
    ('b0000000-0000-0000-0000-000000000001', NULL, 'OWNER',  'Full control including organization management', true),
    ('b0000000-0000-0000-0000-000000000002', NULL, 'ADMIN',  'Manage projects, members, credentials, incidents, alerts', true),
    ('b0000000-0000-0000-0000-000000000003', NULL, 'MEMBER', 'Work with telemetry and incidents', true),
    ('b0000000-0000-0000-0000-000000000004', NULL, 'VIEWER', 'Read-only access', true);

-- OWNER: everything.
INSERT INTO role_permissions (role_id, permission_id)
SELECT 'b0000000-0000-0000-0000-000000000001', id FROM permissions;

-- ADMIN: everything except org.manage.
INSERT INTO role_permissions (role_id, permission_id)
SELECT 'b0000000-0000-0000-0000-000000000002', id FROM permissions
WHERE code <> 'org.manage';

-- MEMBER: reads + incident management.
INSERT INTO role_permissions (role_id, permission_id)
SELECT 'b0000000-0000-0000-0000-000000000003', id FROM permissions
WHERE code IN ('project.read','telemetry.read','incident.read','incident.manage','analytics.read');

-- VIEWER: reads only.
INSERT INTO role_permissions (role_id, permission_id)
SELECT 'b0000000-0000-0000-0000-000000000004', id FROM permissions
WHERE code IN ('project.read','telemetry.read','incident.read','analytics.read');
