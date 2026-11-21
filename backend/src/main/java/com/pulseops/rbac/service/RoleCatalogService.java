package com.pulseops.rbac.service;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Lists the roles available in the current organization with their permission codes. */
@Service
public class RoleCatalogService {

    private final JdbcTemplate jdbc;

    public RoleCatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** RLS shows system roles plus the current organization's custom roles. */
    public List<RoleView> listRoles() {
        return jdbc.query("""
                SELECT r.id, r.name, r.description, r.is_system,
                       array_remove(array_agg(p.code ORDER BY p.code), NULL) AS permissions
                FROM roles r
                LEFT JOIN role_permissions rp ON rp.role_id = r.id
                LEFT JOIN permissions p ON p.id = rp.permission_id
                GROUP BY r.id, r.name, r.description, r.is_system
                ORDER BY r.is_system DESC, count(p.id) DESC, r.name
                """, (rs, i) -> new RoleView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("description"),
                rs.getBoolean("is_system"),
                toList(rs.getArray("permissions"))));
    }

    private static List<String> toList(Array array) throws SQLException {
        return array == null ? List.of()
                : Arrays.stream((Object[]) array.getArray()).map(String.class::cast).toList();
    }

    public record RoleView(UUID id, String name, String description, boolean system, List<String> permissions) {
    }
}
