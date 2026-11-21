package com.pulseops.integration.repository;

import java.util.Optional;
import java.util.UUID;

import com.pulseops.integration.domain.IntegrationConnection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IntegrationConnectionRepository extends JpaRepository<IntegrationConnection, UUID> {

    @Query("select c from IntegrationConnection c where c.organizationId = :orgId and c.serviceId = :serviceId")
    Optional<IntegrationConnection> findForService(@Param("orgId") UUID orgId, @Param("serviceId") UUID serviceId);
}
