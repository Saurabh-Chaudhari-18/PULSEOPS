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

/** A user's membership (and role) in an organization. Removal is a soft status change. */
@Entity
@Table(name = "organization_members")
public class OrganizationMember extends BaseEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Convert(converter = MemberStatus.JpaConverter.class)
    @Column(name = "status", nullable = false)
    private MemberStatus status;

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "joined_at")
    private Instant joinedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrganizationMember() {
    }

    private OrganizationMember(UUID organizationId, UUID userId, UUID roleId, MemberStatus status,
                               UUID invitedBy, Instant joinedAt) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.userId = userId;
        this.roleId = roleId;
        this.status = status;
        this.invitedBy = invitedBy;
        this.joinedAt = joinedAt;
    }

    public static OrganizationMember active(UUID organizationId, UUID userId, UUID roleId, UUID addedBy,
                                            Instant now) {
        return new OrganizationMember(organizationId, userId, roleId, MemberStatus.ACTIVE, addedBy, now);
    }

    public void reactivate(UUID roleId, UUID addedBy, Instant now) {
        this.roleId = roleId;
        this.status = MemberStatus.ACTIVE;
        this.invitedBy = addedBy;
        this.joinedAt = now;
    }

    public void changeRole(UUID roleId) {
        this.roleId = roleId;
    }

    public void remove() {
        this.status = MemberStatus.REMOVED;
    }

    public boolean isActive() {
        return status == MemberStatus.ACTIVE;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public MemberStatus getStatus() {
        return status;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}
