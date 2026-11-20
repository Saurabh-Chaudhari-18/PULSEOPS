-- PulseOps V2: identity — users, organizations, membership, RBAC, refresh tokens.

CREATE TABLE users (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email               text NOT NULL,
    password_hash       text NOT NULL,
    full_name           text NOT NULL,
    status              text NOT NULL DEFAULT 'pending_verification'
                        CHECK (status IN ('pending_verification','active','disabled')),
    email_verified_at   timestamptz,
    last_login_at       timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT users_email_not_blank CHECK (length(trim(email)) > 3)
);
-- Case-insensitive uniqueness without the citext extension.
CREATE UNIQUE INDEX users_email_lower_uq ON users (lower(email));
CREATE TRIGGER users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION pulseops_set_updated_at();

CREATE TABLE organizations (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text NOT NULL CHECK (length(trim(name)) BETWEEN 2 AND 120),
    slug        text NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$'),
    description text,
    status      text NOT NULL DEFAULT 'active'
                CHECK (status IN ('active','suspended','deleted')),
    created_by  uuid REFERENCES users(id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER organizations_updated_at BEFORE UPDATE ON organizations
    FOR EACH ROW EXECUTE FUNCTION pulseops_set_updated_at();

-- Role catalog. organization_id IS NULL => built-in system role.
CREATE TABLE roles (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid REFERENCES organizations(id) ON DELETE CASCADE,
    name            text NOT NULL CHECK (length(trim(name)) BETWEEN 2 AND 60),
    description     text,
    is_system       bool NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (organization_id, name)
);
CREATE TRIGGER roles_updated_at BEFORE UPDATE ON roles
    FOR EACH ROW EXECUTE FUNCTION pulseops_set_updated_at();

CREATE TABLE permissions (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code        text NOT NULL UNIQUE CHECK (code ~ '^[a-z][a-z._]+$'),
    description text NOT NULL
);

CREATE TABLE role_permissions (
    role_id       uuid NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id uuid NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE organization_members (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id         uuid NOT NULL REFERENCES roles(id),
    status          text NOT NULL DEFAULT 'active'
                    CHECK (status IN ('invited','active','removed')),
    invited_by      uuid REFERENCES users(id),
    joined_at       timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, user_id)
);
CREATE INDEX organization_members_user_idx ON organization_members (user_id);
CREATE TRIGGER organization_members_updated_at BEFORE UPDATE ON organization_members
    FOR EACH ROW EXECUTE FUNCTION pulseops_set_updated_at();

-- Opaque rotating refresh tokens; only SHA-256 hashes are stored (ADR-005).
CREATE TABLE refresh_tokens (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash     text NOT NULL UNIQUE,
    issued_at      timestamptz NOT NULL DEFAULT now(),
    expires_at     timestamptz NOT NULL,
    revoked_at     timestamptz,
    replaced_by_id uuid REFERENCES refresh_tokens(id),
    created_ip     inet,
    user_agent     text,
    CONSTRAINT refresh_tokens_expiry_sane CHECK (expires_at > issued_at)
);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);

-- One-time tokens for email verification and password reset (hashes only).
CREATE TABLE user_action_tokens (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose     text NOT NULL CHECK (purpose IN ('email_verification','password_reset')),
    token_hash  text NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX user_action_tokens_user_idx ON user_action_tokens (user_id, purpose);
