package com.pulseops.user.service;

import java.time.Instant;
import java.util.UUID;

import com.pulseops.user.domain.User;
import com.pulseops.user.domain.UserStatus;

/** Public representation of a user. Never includes the password hash. */
public record UserView(UUID id, String email, String fullName, UserStatus status, Instant emailVerifiedAt,
                       Instant lastLoginAt, Instant createdAt) {

    public static UserView from(User user) {
        return new UserView(user.getId(), user.getEmail(), user.getFullName(), user.getStatus(),
                user.getEmailVerifiedAt(), user.getLastLoginAt(), user.getCreatedAt());
    }
}
