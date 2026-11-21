package com.pulseops.common.tenancy;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated user's verified membership in the organization addressed by the
 * current request, with the effective permission codes of their role.
 */
public record OrgAccess(UUID organizationId, UUID userId, UUID memberId, UUID roleId, String roleName,
                        Set<String> permissions) {

    public static final String REQUEST_ATTRIBUTE = OrgAccess.class.getName();

    public OrgAccess {
        permissions = Set.copyOf(permissions);
    }

    public boolean has(String permission) {
        return permissions.contains(permission);
    }

    public boolean isOwner() {
        return "OWNER".equals(roleName);
    }
}
