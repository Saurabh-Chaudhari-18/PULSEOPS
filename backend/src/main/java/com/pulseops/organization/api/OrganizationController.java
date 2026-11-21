package com.pulseops.organization.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.pulseops.common.security.CurrentUser;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.tenancy.Permissions;
import com.pulseops.common.tenancy.RequiresPermission;
import com.pulseops.organization.service.OrganizationService;
import com.pulseops.organization.service.OrganizationViews.OrganizationView;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    @PostMapping
    public ResponseEntity<OrganizationView> create(@Valid @RequestBody CreateOrganizationRequest body) {
        OrganizationView created = organizationService.create(CurrentUser.id(), body.name(), body.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Organizations the caller belongs to (organization switcher). */
    @GetMapping
    public List<OrganizationView> mine() {
        return organizationService.listForUser(CurrentUser.id());
    }

    @GetMapping("/{orgId}")
    public OrganizationView get(@PathVariable UUID orgId, OrgAccess access) {
        return organizationService.get(access);
    }

    @PatchMapping("/{orgId}")
    @RequiresPermission(Permissions.ORG_MANAGE)
    public OrganizationView update(@PathVariable UUID orgId, OrgAccess access,
                                   @Valid @RequestBody UpdateOrganizationRequest body) {
        return organizationService.update(access, body.name(), body.description());
    }

    @DeleteMapping("/{orgId}")
    @RequiresPermission(Permissions.ORG_MANAGE)
    public ResponseEntity<Void> delete(@PathVariable UUID orgId, OrgAccess access,
                                       @Valid @RequestBody DeleteOrganizationRequest body) {
        organizationService.delete(access, body.confirmSlug());
        return ResponseEntity.noContent().build();
    }

    public record CreateOrganizationRequest(
            @NotBlank @Size(min = 2, max = 120) @Pattern(regexp = "^[^\\p{Cntrl}]+$",
                    message = "must not contain control characters") String name,
            @Size(max = 1000) String description) {
    }

    /** Null fields are left unchanged; an empty description clears it. */
    public record UpdateOrganizationRequest(
            @Size(min = 2, max = 120) @Pattern(regexp = "^[^\\p{Cntrl}]+$",
                    message = "must not contain control characters") String name,
            @Size(max = 1000) String description) {
    }

    public record DeleteOrganizationRequest(@NotBlank @Size(max = 64) String confirmSlug) {
    }
}
