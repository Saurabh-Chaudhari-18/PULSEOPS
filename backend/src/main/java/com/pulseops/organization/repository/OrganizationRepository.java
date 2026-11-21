package com.pulseops.organization.repository;

import java.util.UUID;

import com.pulseops.organization.domain.Organization;

import org.springframework.data.jpa.repository.JpaRepository;

/** RLS limits every query to the organization bound in the tenant context. */
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
}
