package com.pulseops.rbac.repository;

import java.util.Optional;
import java.util.UUID;

import com.pulseops.rbac.domain.Role;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    @Query("select r from Role r where r.organizationId is null and r.system = true and r.name = :name")
    Optional<Role> findSystemRole(@Param("name") String name);
}
