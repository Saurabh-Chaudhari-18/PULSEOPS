package com.pulseops.project.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pulseops.common.persistence.BaseEntity;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A monitored application (e.g. "E-Commerce Platform") owned by one organization.
 * Archived projects stay readable but accept no new services or telemetry; deleted
 * projects (soft delete) disappear from every query.
 */
@Entity
@Table(name = "projects")
public class Project extends BaseEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "tech_stack")
    private String techStack;

    @Convert(converter = Environment.JpaConverter.class)
    @Column(name = "environment", nullable = false)
    private Environment environment;

    @Column(name = "region")
    private String region;

    @Column(name = "application_type")
    private String applicationType;

    @Convert(converter = ProjectStatus.JpaConverter.class)
    @Column(name = "status", nullable = false)
    private ProjectStatus status;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Project() {
    }

    public Project(UUID organizationId, ProjectDetails details, UUID createdBy) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.status = ProjectStatus.ACTIVE;
        this.createdBy = createdBy;
        apply(details);
    }

    /** Replaces all editable fields (PATCH semantics are resolved by the service). */
    public void apply(ProjectDetails details) {
        this.name = details.name();
        this.description = details.description();
        this.techStack = details.techStack();
        this.environment = details.environment();
        this.region = details.region();
        this.applicationType = details.applicationType();
    }

    public void archive() {
        this.status = ProjectStatus.ARCHIVED;
    }

    public void restore() {
        this.status = ProjectStatus.ACTIVE;
    }

    public void softDelete(Instant now) {
        this.deletedAt = now;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isActive() {
        return status == ProjectStatus.ACTIVE && deletedAt == null;
    }

    public ProjectDetails details() {
        return new ProjectDetails(name, description, techStack, environment, region, applicationType);
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getName() {
        return name;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
