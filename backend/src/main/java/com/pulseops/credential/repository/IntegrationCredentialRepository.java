package com.pulseops.credential.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.pulseops.credential.domain.CredentialStatus;
import com.pulseops.credential.domain.IntegrationCredential;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Enum values are bound as parameters (not JPQL literals) so the AttributeConverter is
 * applied reliably. Callers use the default methods below.
 */
public interface IntegrationCredentialRepository extends JpaRepository<IntegrationCredential, UUID> {

    @Query("select c from IntegrationCredential c where c.organizationId = :orgId and c.id = :id")
    Optional<IntegrationCredential> findInOrganization(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("""
            select c from IntegrationCredential c
            where c.organizationId = :orgId and c.projectId = :projectId
            order by c.createdAt desc
            """)
    List<IntegrationCredential> findByProject(@Param("orgId") UUID orgId, @Param("projectId") UUID projectId);

    @Modifying
    @Query("""
            update IntegrationCredential c set c.status = :revoked, c.revokedAt = :now
            where c.organizationId = :orgId and c.projectId = :projectId and c.status <> :revoked
            """)
    int revokeWhereProject(@Param("orgId") UUID orgId, @Param("projectId") UUID projectId,
                           @Param("now") Instant now, @Param("revoked") CredentialStatus revoked);

    @Modifying
    @Query("""
            update IntegrationCredential c set c.status = :revoked, c.revokedAt = :now
            where c.organizationId = :orgId and c.serviceId = :serviceId and c.status <> :revoked
            """)
    int revokeWhereService(@Param("orgId") UUID orgId, @Param("serviceId") UUID serviceId,
                           @Param("now") Instant now, @Param("revoked") CredentialStatus revoked);

    @Query("""
            select count(c) from IntegrationCredential c
            where c.organizationId = :orgId and c.projectId = :projectId
              and (c.serviceId is null or c.serviceId = :serviceId)
              and c.status = :active and (c.expiresAt is null or c.expiresAt > :now)
            """)
    long countWithStatusFor(@Param("orgId") UUID orgId, @Param("projectId") UUID projectId,
                            @Param("serviceId") UUID serviceId, @Param("now") Instant now,
                            @Param("active") CredentialStatus active);

    default int revokeAllForProject(UUID orgId, UUID projectId, Instant now) {
        return revokeWhereProject(orgId, projectId, now, CredentialStatus.REVOKED);
    }

    default int revokeAllForService(UUID orgId, UUID serviceId, Instant now) {
        return revokeWhereService(orgId, serviceId, now, CredentialStatus.REVOKED);
    }

    /** Usable (active, unexpired) keys that can send telemetry for the given service. */
    default long countUsableFor(UUID orgId, UUID projectId, UUID serviceId, Instant now) {
        return countWithStatusFor(orgId, projectId, serviceId, now, CredentialStatus.ACTIVE);
    }
}
