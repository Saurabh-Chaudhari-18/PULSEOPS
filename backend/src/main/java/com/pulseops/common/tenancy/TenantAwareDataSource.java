package com.pulseops.common.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Applies the current {@link TenantContext} to every connection handed out by the pool.
 *
 * <p>Every checkout overwrites BOTH settings (empty string when unset), so a pooled
 * connection can never carry a previous request's tenant. Empty values make
 * {@code pulseops_current_org_id()} return NULL, and RLS policies then match no rows
 * (fail closed).</p>
 *
 * <p>The settings are session-level ({@code is_local = false}) and executed in
 * autocommit mode (committed explicitly otherwise), so a later transaction rollback
 * cannot revert them to a stale tenant.</p>
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    static final String APPLY_SQL =
            "SELECT set_config('app.current_org_id', ?, false), set_config('app.current_user_id', ?, false)";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return applyTenant(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return applyTenant(super.getConnection(username, password));
    }

    static Connection applyTenant(Connection connection) throws SQLException {
        try {
            boolean autoCommit = connection.getAutoCommit();
            try (PreparedStatement ps = connection.prepareStatement(APPLY_SQL)) {
                ps.setString(1, asSetting(TenantContext.organizationId()));
                ps.setString(2, asSetting(TenantContext.userId()));
                ps.execute();
            }
            if (!autoCommit) {
                connection.commit();
            }
            return connection;
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    private static String asSetting(UUID id) {
        return id == null ? "" : id.toString();
    }
}
