package com.pulseops.credential.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.pulseops.common.error.ApiException;
import com.pulseops.common.tenancy.TenantContext;
import com.pulseops.credential.domain.IntegrationKeys;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Authenticates integration keys presented by customer applications.
 *
 * <p>Runs before any tenant is known. The lookup binds the key's hash as the
 * transaction-local setting {@code app.credential_hash}; the V10 RLS policy then reveals
 * exactly that credential row and nothing else. The setting ends with the transaction.</p>
 */
@Service
public class CredentialAuthenticator {

    static final long LAST_USED_WRITE_INTERVAL_MS = 60_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Long> lastUsedWrites = new ConcurrentHashMap<>();

    public CredentialAuthenticator(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    /** The scope granted by a valid key. {@code serviceId} is null for project-wide keys. */
    public record AuthenticatedKey(UUID credentialId, UUID organizationId, UUID projectId, UUID serviceId) {
    }

    private record Row(UUID id, UUID organizationId, UUID projectId, UUID serviceId, String status,
                       Instant expiresAt) {
    }

    public AuthenticatedKey authenticate(String rawKey) {
        if (!IntegrationKeys.looksValid(rawKey)) {
            throw failure("INVALID_CREDENTIAL", "Missing or malformed integration key");
        }
        String hash = IntegrationKeys.hash(rawKey);
        if (TenantContext.organizationId() != null) {
            throw new IllegalStateException("Credential authentication must run without a tenant context");
        }
        List<Row> rows = tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.credential_hash', ?, true)", String.class, hash);
            return jdbc.query("""
                    SELECT id, organization_id, project_id, service_id, status, expires_at
                    FROM integration_credentials WHERE secret_hash = ?
                    """, (rs, i) -> new Row(
                    rs.getObject("id", UUID.class),
                    rs.getObject("organization_id", UUID.class),
                    rs.getObject("project_id", UUID.class),
                    rs.getObject("service_id", UUID.class),
                    rs.getString("status"),
                    toInstant(rs.getTimestamp("expires_at"))), hash);
        });
        if (rows == null || rows.isEmpty()) {
            throw failure("INVALID_CREDENTIAL", "Unknown integration key");
        }
        Row row = rows.get(0);
        Instant now = clock.instant();
        if ("revoked".equals(row.status())) {
            throw failure("CREDENTIAL_REVOKED", "This integration key has been revoked");
        }
        if ("expired".equals(row.status()) || (row.expiresAt() != null && !now.isBefore(row.expiresAt()))) {
            throw failure("CREDENTIAL_EXPIRED", "This integration key has expired");
        }
        return new AuthenticatedKey(row.id(), row.organizationId(), row.projectId(), row.serviceId());
    }

    /**
     * Records last-used time, at most once a minute per key to avoid a write per batch.
     * Must be called with the key's organization bound as tenant context.
     */
    public void markUsed(AuthenticatedKey key) {
        long nowMs = clock.millis();
        Long previous = lastUsedWrites.get(key.credentialId());
        if (previous != null && nowMs - previous < LAST_USED_WRITE_INTERVAL_MS) {
            return;
        }
        lastUsedWrites.put(key.credentialId(), nowMs);
        jdbc.update("UPDATE integration_credentials SET last_used_at = ? WHERE id = ? AND organization_id = ?",
                Timestamp.from(clock.instant()), key.credentialId(), key.organizationId());
    }

    private static ApiException failure(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
