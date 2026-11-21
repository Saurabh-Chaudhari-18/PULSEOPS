package com.pulseops.appservice.domain;

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
 * A deployable unit inside a project (e.g. "Payment Service") that sends telemetry.
 * Named MonitoredService to avoid confusion with Spring's {@code @Service}.
 */
@Entity
@Table(name = "services")
public class MonitoredService extends BaseEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Convert(converter = Technology.JpaConverter.class)
    @Column(name = "technology", nullable = false)
    private Technology technology;

    @Convert(converter = ServiceStatus.JpaConverter.class)
    @Column(name = "status", nullable = false)
    private ServiceStatus status;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MonitoredService() {
    }

    public MonitoredService(UUID organizationId, UUID projectId, String name, String description,
                            Technology technology) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.name = name;
        this.description = description;
        this.technology = technology == null ? Technology.OTHER : technology;
        this.status = ServiceStatus.ACTIVE;
    }

    public void update(String name, String description, Technology technology) {
        if (name != null) {
            this.name = name.trim();
        }
        if (description != null) {
            this.description = description.isBlank() ? null : description.trim();
        }
        if (technology != null) {
            this.technology = technology;
        }
    }

    public void archive() {
        this.status = ServiceStatus.ARCHIVED;
    }

    public void restore() {
        this.status = ServiceStatus.ACTIVE;
    }

    public void softDelete(Instant now) {
        this.deletedAt = now;
    }

    public boolean isActive() {
        return status == ServiceStatus.ACTIVE && deletedAt == null;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Technology getTechnology() {
        return technology;
    }

    public ServiceStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
