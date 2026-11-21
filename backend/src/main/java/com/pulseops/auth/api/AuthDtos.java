package com.pulseops.auth.api;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.pulseops.user.service.UserView;

/** Request/response bodies of the auth endpoints. Password strength is checked by PasswordPolicy. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password,
            @NotBlank @Size(min = 2, max = 120) @Pattern(regexp = "^[^\\p{Cntrl}]+$",
                    message = "must not contain control characters") String fullName) {
    }

    public record RegisterResponse(String status, String message) {
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, Instant expiresAt, UserView user) {
    }

    public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    public record TokenRequest(@NotBlank @Size(max = 200) String token) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(max = 200) String token,
            @NotBlank @Size(max = 128) String newPassword) {
    }

    public record MessageResponse(String message) {
    }
}
