package com.pulseops.project.repository;

import java.util.Optional;
import java.util.UUID;

import com.pulseops.project.domain.Project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every query filters by organization and excludes soft-deleted rows; RLS is the backstop. */
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    @Query("""
            select p from Project p
            where p.organizationId = :orgId and p.id = :projectId and p.deletedAt is null
            """)
    Optional<Project> findLive(@Param("orgId") UUID orgId, @Param("projectId") UUID projectId);

    @Query("""
            select count(p) > 0 from Project p
            where p.organizationId = :orgId and lower(p.name) = lower(:name) and p.deletedAt is null
            """)
    boolean nameTaken(@Param("orgId") UUID orgId, @Param("name") String name);

    @Query("""
            select count(p) > 0 from Project p
            where p.organizationId = :orgId and lower(p.name) = lower(:name) and p.deletedAt is null
              and p.id <> :excludeId
            """)
    boolean nameTakenByOther(@Param("orgId") UUID orgId, @Param("name") String name,
                             @Param("excludeId") UUID excludeId);
}
