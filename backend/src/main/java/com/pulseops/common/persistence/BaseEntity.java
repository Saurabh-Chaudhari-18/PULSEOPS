package com.pulseops.common.persistence;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * Base for entities with application-assigned UUIDs.
 *
 * <p>IDs are generated in Java (not by the database) so a tenant context can be bound
 * to an organization's ID before the row exists — required by Row-Level Security when
 * creating an organization. {@link Persistable} lets Spring Data issue a plain INSERT
 * for new entities instead of a merge-with-select.</p>
 */
@MappedSuperclass
public abstract class BaseEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    /** For JPA only. */
    protected BaseEntity() {
    }

    protected BaseEntity(UUID id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return id != null && id.equals(((BaseEntity) o).id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
