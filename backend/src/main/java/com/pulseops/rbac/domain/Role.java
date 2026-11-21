package com.pulseops.rbac.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pulseops.common.persistence.BaseEntity;

/** A role. System roles (organizationId == null) are seeded by migration and read-only. */
@Entity
@Table(name = "roles")
public class Role extends BaseEntity {

    public static final String OWNER = "OWNER";
    public static final String ADMIN = "ADMIN";
    public static final String MEMBER = "MEMBER";
    public static final String VIEWER = "VIEWER";

    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "is_system", nullable = false, updatable = false)
    private boolean system;

    protected Role() {
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isSystem() {
        return system;
    }

    public boolean isOwnerRole() {
        return system && OWNER.equals(name);
    }
}
