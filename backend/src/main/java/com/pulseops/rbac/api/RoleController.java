package com.pulseops.rbac.api;

import java.util.List;
import java.util.UUID;

import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.rbac.service.RoleCatalogService;
import com.pulseops.rbac.service.RoleCatalogService.RoleView;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
public class RoleController {

    private final RoleCatalogService roles;

    public RoleController(RoleCatalogService roles) {
        this.roles = roles;
    }

    /** Roles assignable in this organization (any member may view). */
    @GetMapping("/roles")
    public List<RoleView> list(@PathVariable UUID orgId) {
        return roles.listRoles();
    }

    /** The caller's own role and permissions here — lets the UI hide actions it cannot perform. */
    @GetMapping("/access")
    public AccessView myAccess(@PathVariable UUID orgId, OrgAccess access) {
        return new AccessView(access.organizationId(), access.roleName(),
                access.permissions().stream().sorted().toList());
    }

    public record AccessView(UUID organizationId, String role, List<String> permissions) {
    }
}
