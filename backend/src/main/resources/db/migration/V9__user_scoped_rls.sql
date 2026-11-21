-- PulseOps V9: user-scoped RLS visibility.
--
-- Before an organization is selected, a signed-in user must be able to list THEIR OWN
-- memberships and the organizations they belong to (organization switcher, access checks).
-- The backend binds the authenticated user per connection:
--     set_config('app.current_user_id', '<uuid>', false)
-- These policies are PERMISSIVE: they are OR-ed with the tenant policies from V8 and only
-- ever widen visibility to rows of the authenticated user's own memberships.

CREATE FUNCTION pulseops_current_user_id() RETURNS uuid
LANGUAGE sql STABLE AS $$
    SELECT NULLIF(current_setting('app.current_user_id', true), '')::uuid
$$;

-- A user can read their own membership rows in any organization.
CREATE POLICY member_self_read ON organization_members FOR SELECT
    USING (user_id = pulseops_current_user_id());

-- A user can read organizations where they hold an ACTIVE membership.
-- (The subquery is evaluated under organization_members' own policies, which do not
-- reference organizations — no policy recursion.)
CREATE POLICY org_member_read ON organizations FOR SELECT
    USING (EXISTS (
        SELECT 1
        FROM organization_members m
        WHERE m.organization_id = organizations.id
          AND m.user_id = pulseops_current_user_id()
          AND m.status = 'active'));
