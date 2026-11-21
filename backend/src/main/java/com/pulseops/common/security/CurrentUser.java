package com.pulseops.common.security;

import java.util.UUID;

import com.pulseops.common.error.ApiException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Access to the authenticated user's ID (the JWT subject). */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** The authenticated user's ID, or null for anonymous requests. */
    public static UUID idOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            try {
                return UUID.fromString(jwt.getName());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    public static UUID id() {
        UUID id = idOrNull();
        if (id == null) {
            throw ApiException.unauthorized("UNAUTHENTICATED", "Authentication required");
        }
        return id;
    }
}
