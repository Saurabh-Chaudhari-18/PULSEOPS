package com.pulseops.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import com.pulseops.auth.api.AuthDtos.EmailRequest;
import com.pulseops.auth.api.AuthDtos.LoginRequest;
import com.pulseops.auth.api.AuthDtos.MessageResponse;
import com.pulseops.auth.api.AuthDtos.RegisterRequest;
import com.pulseops.auth.api.AuthDtos.RegisterResponse;
import com.pulseops.auth.api.AuthDtos.ResetPasswordRequest;
import com.pulseops.auth.api.AuthDtos.TokenRequest;
import com.pulseops.auth.api.AuthDtos.TokenResponse;
import com.pulseops.auth.service.AuthService;
import com.pulseops.auth.service.AuthService.LoginResult;
import com.pulseops.auth.service.AuthService.RegistrationOutcome;
import com.pulseops.common.security.AuthCsrfGuard;
import com.pulseops.common.web.ClientInfo;
import com.pulseops.user.domain.User;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public authentication endpoints. See docs/API.md for request/response contracts. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String ACCEPTED_EMAIL_MESSAGE =
            "If the address is eligible, an email is on its way.";

    private final AuthService authService;
    private final AuthRateLimits rateLimits;
    private final RefreshCookie refreshCookie;

    public AuthController(AuthService authService, AuthRateLimits rateLimits, RefreshCookie refreshCookie) {
        this.authService = authService;
        this.rateLimits = rateLimits;
        this.refreshCookie = refreshCookie;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest body,
                                                     HttpServletRequest request) {
        rateLimits.register(ClientInfo.from(request).ip());
        RegistrationOutcome outcome = authService.register(body.email(), body.password(), body.fullName());
        if (outcome == RegistrationOutcome.ACTIVE) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new RegisterResponse("active", "Account created. You can sign in now."));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new RegisterResponse("verification_pending",
                "Check your inbox for a verification link to activate your account."));
    }

    @PostMapping("/verify-email")
    public MessageResponse verifyEmail(@Valid @RequestBody TokenRequest body, HttpServletRequest request) {
        rateLimits.tokenSubmission(ClientInfo.from(request).ip());
        authService.verifyEmail(body.token());
        return new MessageResponse("Email verified. You can sign in now.");
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<MessageResponse> resendVerification(@Valid @RequestBody EmailRequest body,
                                                              HttpServletRequest request) {
        rateLimits.emailSending(ClientInfo.from(request).ip(), User.normalizeEmail(body.email()));
        authService.resendVerification(body.email());
        return ResponseEntity.accepted().body(new MessageResponse(ACCEPTED_EMAIL_MESSAGE));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        ClientInfo client = ClientInfo.from(request);
        rateLimits.login(client.ip(), User.normalizeEmail(body.email()));
        LoginResult result = authService.login(body.email(), body.password(), client.userAgent());
        return withSession(result);
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = RefreshCookie.NAME, required = false) String refreshToken,
            HttpServletRequest request) {
        AuthCsrfGuard.require(request);
        ClientInfo client = ClientInfo.from(request);
        rateLimits.refresh(client.ip());
        LoginResult result = authService.refresh(refreshToken, client.userAgent());
        return withSession(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshCookie.NAME, required = false) String refreshToken,
            HttpServletRequest request) {
        AuthCsrfGuard.require(request);
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString())
                .build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<MessageResponse> forgotPassword(@Valid @RequestBody EmailRequest body,
                                                          HttpServletRequest request) {
        rateLimits.emailSending(ClientInfo.from(request).ip(), User.normalizeEmail(body.email()));
        authService.forgotPassword(body.email());
        return ResponseEntity.accepted().body(new MessageResponse(ACCEPTED_EMAIL_MESSAGE));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest body,
                                              HttpServletRequest request) {
        rateLimits.tokenSubmission(ClientInfo.from(request).ip());
        authService.resetPassword(body.token(), body.newPassword());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString())
                .build();
    }

    private ResponseEntity<TokenResponse> withSession(LoginResult result) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie
                        .issue(result.refreshToken().rawToken(), result.refreshToken().expiresAt()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new TokenResponse(result.accessToken().value(), "Bearer",
                        result.accessToken().expiresAt(), result.user()));
    }
}
