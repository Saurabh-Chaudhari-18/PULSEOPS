package com.pulseops.organization.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import com.pulseops.organization.domain.MemberStatus;
import com.pulseops.organization.domain.OrganizationMember;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every query filters by organization explicitly (application-level isolation); RLS is the
 * backstop (ADR-006).
 */
public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, UUID> {

    @Query("select m from OrganizationMember m where m.organizationId = :orgId and m.id = :memberId")
    Optional<OrganizationMember> findInOrganization(@Param("orgId") UUID orgId, @Param("memberId") UUID memberId);

    @Query("select m from OrganizationMember m where m.organizationId = :orgId and m.userId = :userId")
    Optional<OrganizationMember> findByUser(@Param("orgId") UUID orgId, @Param("userId") UUID userId);

    /**
     * Locks the organization's active members holding {@code roleId}. Used for last-owner
     * protection so two concurrent demotions cannot both succeed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select m from OrganizationMember m
            where m.organizationId = :orgId and m.roleId = :roleId and m.status = :status
            """)
    List<OrganizationMember> lockByRole(@Param("orgId") UUID orgId, @Param("roleId") UUID roleId,
                                        @Param("status") MemberStatus status);
}
