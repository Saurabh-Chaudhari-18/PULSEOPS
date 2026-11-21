package com.pulseops.user.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.pulseops.common.security.CurrentUser;
import com.pulseops.user.service.UserService;
import com.pulseops.user.service.UserView;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The authenticated user's own profile. Password/session endpoints live in the auth module. */
@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public UserView me() {
        return userService.profile(CurrentUser.id());
    }

    @PatchMapping
    public UserView update(@Valid @RequestBody UpdateProfileRequest body) {
        return userService.rename(CurrentUser.id(), body.fullName());
    }

    public record UpdateProfileRequest(
            @NotBlank @Size(min = 2, max = 120) @Pattern(regexp = "^[^\\p{Cntrl}]+$",
                    message = "must not contain control characters") String fullName) {
    }
}
