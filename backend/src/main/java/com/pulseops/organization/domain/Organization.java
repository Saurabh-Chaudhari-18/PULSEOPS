package com.pulseops.organization.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pulseops.common.persistence.BaseEntity;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** A tenant. Every tenant-owned row in PulseOps references an organization. */
@Entity
@Table(name = "organizations")
public class Organization extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "slug", nullable = false, updatable = false)
    private String slug;

    @Column(name = "description")
    private String description;

    @Convert(converter = OrganizationStatus.JpaConverter.class)
    @Column(name = "status", nullable = false)
    private OrganizationStatus status;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Organization() {
    }

    /** The ID is supplied so the tenant context can be bound to it before insertion (RLS). */
    public Organization(UUID id, String name, String slug, String description, UUID createdBy) {
        super(id);
        this.name = name;
        this.slug = slug;
        this.description = description;
        this.status = OrganizationStatus.ACTIVE;
        this.createdBy = createdBy;
    }

    public void update(String name, String description) {
        if (name != null) {
            this.name = name;
        }
        if (description != null) {
            this.description = description.isBlank() ? null : description;
        }
    }

    public void markDeleted() {
        this.status = OrganizationStatus.DELETED;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getDescription() {
        return description;
    }

    public OrganizationStatus getStatus() {
        return status;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
