package com.pulseops.common.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a user's active membership in an active organization. Implemented by the
 * RBAC module; kept as an interface so {@code common} does not depend on a feature module.
 */
public interface OrgAccessResolver {

    Optional<OrgAccess> resolve(UUID organizationId, UUID userId);
}
