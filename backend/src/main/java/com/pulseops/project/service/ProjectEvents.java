package com.pulseops.project.service;

import java.util.UUID;

/**
 * Domain events published synchronously inside the originating transaction, so listeners'
 * changes (e.g. credential revocation) commit or roll back together with the cause.
 */
public final class ProjectEvents {

    private ProjectEvents() {
    }

    /** A project was archived or soft-deleted: its telemetry must stop being accepted. */
    public record ProjectDeactivated(UUID organizationId, UUID projectId, boolean deleted, UUID actorId) {
    }

    /** A service was archived or soft-deleted. */
    public record ServiceDeactivated(UUID organizationId, UUID projectId, UUID serviceId, boolean deleted,
                                     UUID actorId) {
    }
}
