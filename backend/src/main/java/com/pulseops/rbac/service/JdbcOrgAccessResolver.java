package com.pulseops.rbac.service;

import java.sql.Array;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.tenancy.OrgAccessResolver;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Resolves membership + permissions in one query. Runs with only the user context bound
 * (no organization yet), so it relies on the V9 user-scoped policies: a user can see their
 * own membership rows and the organizations they actively belong to — nothing else.
 */
@Service
public class JdbcOrgAccessResolver implements OrgAccessResolver {

    private static final String SQL = """
            SELECT m.id AS member_id, r.id AS role_id, r.name AS role_name,
                   array_remove(array_agg(p.code), NULL) AS permissions
            FROM organization_members m
            JOIN organizations o ON o.id = m.organization_id
            JOIN roles r ON r.id = m.role_id
            LEFT JOIN role_permissions rp ON rp.role_id = r.id
            LEFT JOIN permissions p ON p.id = rp.permission_id
            WHERE m.organization_id = ? AND m.user_id = ?
              AND m.status = 'active' AND o.status = 'active'
            GROUP BY m.id, r.id, r.name
            """;

    private final JdbcTemplate jdbc;

    public JdbcOrgAccessResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<OrgAccess> resolve(UUID organizationId, UUID userId) {
        List<OrgAccess> rows = jdbc.query(SQL, (rs, i) -> new OrgAccess(
                organizationId,
                userId,
                rs.getObject("member_id", UUID.class),
                rs.getObject("role_id", UUID.class),
                rs.getString("role_name"),
                toSet(rs.getArray("permissions"))), organizationId, userId);
        return rows.stream().findFirst();
    }

    private static Set<String> toSet(Array array) throws SQLException {
        if (array == null) {
            return Set.of();
        }
        Object[] values = (Object[]) array.getArray();
        Set<String> result = new HashSet<>(values.length);
        for (Object v : values) {
            result.add((String) v);
        }
        return result;
    }
}
