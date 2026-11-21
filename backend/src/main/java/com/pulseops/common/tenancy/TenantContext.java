package com.pulseops.common.tenancy;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Thread-bound tenant identity, propagated into PostgreSQL by {@link TenantAwareDataSource}
 * as {@code app.current_org_id} / {@code app.current_user_id} for Row-Level Security.
 *
 * <p><b>Rule:</b> the context must be set BEFORE a transaction begins — the database
 * settings are applied when a connection is checked out of the pool. Background jobs must
 * set the context explicitly (there is no RLS bypass).</p>
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> ORGANIZATION = new ThreadLocal<>();
    private static final ThreadLocal<UUID> USER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static UUID organizationId() {
        return ORGANIZATION.get();
    }

    public static UUID userId() {
        return USER.get();
    }

    public static void setOrganizationId(UUID organizationId) {
        set(ORGANIZATION, organizationId);
    }

    public static void setUserId(UUID userId) {
        set(USER, userId);
    }

    public static void clear() {
        ORGANIZATION.remove();
        USER.remove();
    }

    /** Runs {@code action} with the organization context temporarily switched to {@code organizationId}. */
    public static <T> T callInOrganization(UUID organizationId, Supplier<T> action) {
        UUID previous = ORGANIZATION.get();
        set(ORGANIZATION, organizationId);
        try {
            return action.get();
        } finally {
            set(ORGANIZATION, previous);
        }
    }

    private static void set(ThreadLocal<UUID> holder, UUID value) {
        if (value == null) {
            holder.remove();
        } else {
            holder.set(value);
        }
    }
}
