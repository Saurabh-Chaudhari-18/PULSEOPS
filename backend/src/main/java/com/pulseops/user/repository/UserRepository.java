package com.pulseops.user.repository;

import java.util.Optional;
import java.util.UUID;

import com.pulseops.user.domain.User;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Matches the case-insensitive unique index {@code users_email_lower_uq}; pass a normalized email. */
    @Query("select u from User u where lower(u.email) = :email")
    Optional<User> findByNormalizedEmail(@Param("email") String normalizedEmail);
}
