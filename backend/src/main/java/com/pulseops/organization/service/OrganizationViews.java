package com.pulseops.organization.service;

import java.time.Instant;
import java.util.UUID;

import com.pulseops.organization.domain.MemberStatus;
import com.pulseops.organization.domain.Organization;
import com.pulseops.organization.domain.OrganizationStatus;

/** Read models returned by the organization module. */
public final class OrganizationViews {

    private OrganizationViews() {
    }

    public record OrganizationView(UUID id, String name, String slug, String description,
                                   OrganizationStatus status, String myRole, Instant createdAt) {

        public static OrganizationView from(Organization org, String myRole) {
            return new OrganizationView(org.getId(), org.getName(), org.getSlug(), org.getDescription(),
                    org.getStatus(), myRole, org.getCreatedAt());
        }
    }

    public record MemberView(UUID memberId, UUID userId, String email, String fullName, UUID roleId,
                             String role, MemberStatus status, Instant joinedAt) {
    }
}
