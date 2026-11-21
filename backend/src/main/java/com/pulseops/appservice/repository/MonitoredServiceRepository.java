package com.pulseops.appservice.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.pulseops.appservice.domain.MonitoredService;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonitoredServiceRepository extends JpaRepository<MonitoredService, UUID> {

    @Query("""
            select s from MonitoredService s
            where s.organizationId = :orgId and s.id = :serviceId and s.deletedAt is null
            """)
    Optional<MonitoredService> findLive(@Param("orgId") UUID orgId, @Param("serviceId") UUID serviceId);

    @Query("""
            select s from MonitoredService s
            where s.organizationId = :orgId and s.projectId = :projectId and s.deletedAt is null
            order by lower(s.name)
            """)
    List<MonitoredService> findLiveByProject(@Param("orgId") UUID orgId, @Param("projectId") UUID projectId);

    /** Case-insensitive lookup used by ingestion when a batch names its service. */
    @Query("""
            select s from MonitoredService s
            where s.organizationId = :orgId and s.projectId = :projectId
              and lower(s.name) = lower(:name) and s.deletedAt is null
            """)
    Optional<MonitoredService> findLiveByName(@Param("orgId") UUID orgId, @Param("projectId") UUID projectId,
                                              @Param("name") String name);

    @Query("""
            select count(s) > 0 from MonitoredService s
            where s.projectId = :projectId and lower(s.name) = lower(:name) and s.deletedAt is null
            """)
    boolean nameTaken(@Param("projectId") UUID projectId, @Param("name") String name);

    @Query("""
            select count(s) > 0 from MonitoredService s
            where s.projectId = :projectId and lower(s.name) = lower(:name) and s.deletedAt is null
              and s.id <> :excludeId
            """)
    boolean nameTakenByOther(@Param("projectId") UUID projectId, @Param("name") String name,
                             @Param("excludeId") UUID excludeId);

    @Modifying
    @Query("""
            update MonitoredService s set s.deletedAt = :now
            where s.organizationId = :orgId and s.projectId = :projectId and s.deletedAt is null
            """)
    int softDeleteByProject(@Param("orgId") UUID orgId, @Param("projectId") UUID projectId,
                            @Param("now") Instant now);
}
