package com.pulseops.common.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Tenant isolation end-to-end against a real PostgreSQL: Flyway migrations + RLS +
 * {@link TenantAwareDataSource} over a pool of exactly ONE connection, so every query reuses
 * the same physical connection and any leaked tenant setting would be observed.
 *
 * <p>Runs only when PULSEOPS_TEST_DB_URL is set (a dedicated, disposable database; the app
 * role must NOT be a superuser or BYPASSRLS). Example:
 * <pre>
 * PULSEOPS_TEST_DB_URL=jdbc:postgresql://localhost:5432/pulseops_it
 * PULSEOPS_TEST_DB_USER=pulseops_app PULSEOPS_TEST_DB_PASSWORD=... mvn test
 * </pre></p>
 */
@EnabledIfEnvironmentVariable(named = "PULSEOPS_TEST_DB_URL", matches = ".+")
class TenantIsolationIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;

    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();

    @BeforeAll
    static void migrate() {
        String url = System.getenv("PULSEOPS_TEST_DB_URL");
        String user = System.getenv().getOrDefault("PULSEOPS_TEST_DB_USER", "pulseops_app");
        String password = System.getenv("PULSEOPS_TEST_DB_PASSWORD");
        // Flyway needs two connections (schema-history lock + migration), so it must not use
        // the deliberately single-connection pool below.
        Flyway.configure().dataSource(new DriverManagerDataSource(url, user, password))
                .locations("classpath:db/migration").load().migrate();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(1);
        pool = new HikariDataSource(config);
        jdbc = new JdbcTemplate(new TenantAwareDataSource(pool));
    }

    @AfterAll
    static void close() {
        if (pool != null) {
            pool.close();
        }
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void tenantsAreIsolatedAndContextNeverLeaksAcrossCheckouts() {
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'x', 'Alice', 'active')",
                alice, "alice-" + alice + "@it.test");
        createOrg(orgA);
        createOrg(orgB);

        TenantContext.setOrganizationId(orgA);
        jdbc.update("INSERT INTO projects (organization_id, name) VALUES (?, 'Project A')", orgA);
        assertThat(count("projects")).isEqualTo(1);

        // Cross-tenant write is rejected by RLS even though the code "asked" for it.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO projects (organization_id, name) VALUES (?, 'evil')", orgB))
                .hasMessageContaining("row-level security");

        // Same physical connection, different tenant: A's data is invisible.
        TenantContext.setOrganizationId(orgB);
        assertThat(count("projects")).isZero();

        // Context cleared: the reused connection must fail closed, not keep tenant B (or A).
        TenantContext.clear();
        assertThat(count("projects")).isZero();
        assertThat(count("organizations")).isZero();
    }

    @Test
    void userContextRevealsOnlyOwnMemberships() {
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'x', 'Alice', 'active')",
                alice, "alice-" + alice + "@it.test");
        createOrg(orgA);
        createOrg(orgB);
        TenantContext.setOrganizationId(orgA);
        jdbc.update("""
                INSERT INTO organization_members (organization_id, user_id, role_id, status)
                VALUES (?, ?, 'b0000000-0000-0000-0000-000000000001', 'active')""", orgA, alice);
        TenantContext.clear();

        TenantContext.setUserId(alice);
        assertThat(jdbc.queryForList("SELECT id FROM organizations", UUID.class)).containsExactly(orgA);
        assertThat(count("organization_members")).isEqualTo(1);

        TenantContext.setUserId(UUID.randomUUID()); // someone else
        assertThat(count("organizations")).isZero();
    }

    private void createOrg(UUID id) {
        TenantContext.setOrganizationId(id);
        jdbc.update("INSERT INTO organizations (id, name, slug) VALUES (?, ?, ?)",
                id, "Org " + id, "org-" + id.toString().substring(0, 8));
        TenantContext.clear();
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
