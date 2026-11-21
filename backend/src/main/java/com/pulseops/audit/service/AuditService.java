package com.pulseops.audit.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseops.common.tenancy.TenantContext;
import com.pulseops.common.web.ClientInfo;
import com.pulseops.common.web.PageQuery;
import com.pulseops.common.web.PageResponse;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Append-only audit trail.
 *
 * <p>The organization is always taken from {@link TenantContext} — never from the caller —
 * so an entry can only be written into the tenant the current transaction is bound to
 * (RLS enforces the same). Platform-level events (registration, login) are written with no
 * organization context. Writes join the caller's transaction, so an audited change and its
 * audit entry commit or roll back together.</p>
 *
 * <p>Before inserting, pending JPA changes are flushed: the audit row is written with plain
 * JDBC and references (by foreign key) rows such as a just-registered user or a just-created
 * organization, which Hibernate would otherwise only write at commit. Found by the first
 * end-to-end run against PostgreSQL (registration failed with an FK violation).</p>
 */
@Service
public class AuditService {

    private static final String INSERT_SQL = """
            INSERT INTO audit_logs (organization_id, actor_id, action, resource_type, resource_id,
                                    ip_address, user_agent, details)
            VALUES (?, ?, ?, ?, ?, ?::inet, ?, ?::jsonb)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    public AuditService(JdbcTemplate jdbc, ObjectMapper objectMapper, EntityManager entityManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
    }

    public void record(UUID actorId, String action, String resourceType, UUID resourceId, Map<String, ?> details) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            entityManager.flush();
        }
        ClientInfo client = ClientInfo.current();
        jdbc.update(INSERT_SQL,
                TenantContext.organizationId(),
                actorId,
                action,
                resourceType,
                resourceId,
                client.ip(),
                client.userAgent(),
                toJson(details));
    }

    /** Audit entries of the current organization, newest first. */
    public PageResponse<AuditEntryView> list(PageQuery page, String actionPrefix) {
        UUID orgId = TenantContext.organizationId();
        String filter = actionPrefix == null || actionPrefix.isBlank() ? null : actionPrefix.trim() + "%";
        long total = jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs
                WHERE organization_id = ? AND (?::text IS NULL OR action LIKE ?)
                """, Long.class, orgId, filter, filter);
        List<AuditEntryView> rows = jdbc.query("""
                SELECT a.id, a.actor_id, u.email AS actor_email, a.action, a.resource_type, a.resource_id,
                       host(a.ip_address) AS ip, a.details::text AS details, a.created_at
                FROM audit_logs a
                LEFT JOIN users u ON u.id = a.actor_id
                WHERE a.organization_id = ? AND (?::text IS NULL OR a.action LIKE ?)
                ORDER BY a.created_at DESC
                LIMIT ? OFFSET ?
                """, (rs, i) -> new AuditEntryView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("actor_id", UUID.class),
                        rs.getString("actor_email"),
                        rs.getString("action"),
                        rs.getString("resource_type"),
                        rs.getObject("resource_id", UUID.class),
                        rs.getString("ip"),
                        rs.getString("details"),
                        toInstant(rs.getTimestamp("created_at"))),
                orgId, filter, filter, page.size(), page.offset());
        return PageResponse.of(rows, page, total);
    }

    private String toJson(Map<String, ?> details) {
        try {
            return objectMapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Audit details must be JSON-serializable", e);
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public record AuditEntryView(UUID id, UUID actorId, String actorEmail, String action, String resourceType,
                                 UUID resourceId, String ipAddress, String detailsJson, Instant createdAt) {
    }
}
