package com.pulseops.auth.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.pulseops.auth.service.AccountSecurityService;
import com.pulseops.common.security.CurrentUser;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated (bearer) password and session management for the current user. */
@RestController
@RequestMapping("/api/v1/users/me")
public class AccountSecurityController {

    private final AccountSecurityService accountSecurity;
    private final RefreshCookie refreshCookie;

    public AccountSecurityController(AccountSecurityService accountSecurity, RefreshCookie refreshCookie) {
        this.accountSecurity = accountSecurity;
        this.refreshCookie = refreshCookie;
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest body) {
        accountSecurity.changePassword(CurrentUser.id(), body.currentPassword(), body.newPassword());
        return signedOut();
    }

    @PostMapping("/sessions/revoke-all")
    public ResponseEntity<Void> revokeAllSessions() {
        accountSecurity.revokeAllSessions(CurrentUser.id());
        return signedOut();
    }

    private ResponseEntity<Void> signedOut() {
        // The cookie is path-scoped to /api/v1/auth; clearing it requires the same path,
        // which RefreshCookie applies.
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString()).build();
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(max = 128) String newPassword) {
    }
}
