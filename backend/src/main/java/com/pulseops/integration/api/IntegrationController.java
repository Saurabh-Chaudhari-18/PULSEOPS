package com.pulseops.integration.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.tenancy.Permissions;
import com.pulseops.common.tenancy.RequiresPermission;
import com.pulseops.integration.domain.IntegrationType;
import com.pulseops.integration.service.InstallationGuideService;
import com.pulseops.integration.service.InstallationGuideService.InstallationGuide;
import com.pulseops.integration.service.IntegrationService;
import com.pulseops.integration.service.IntegrationService.ConnectionStatusView;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Onboarding wizard endpoints for one service's integration. */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/services/{serviceId}/integration")
public class IntegrationController {

    private final IntegrationService integrationService;
    private final InstallationGuideService guides;

    public IntegrationController(IntegrationService integrationService, InstallationGuideService guides) {
        this.integrationService = integrationService;
        this.guides = guides;
    }

    @GetMapping
    @RequiresPermission(Permissions.PROJECT_READ)
    public ConnectionStatusView status(@PathVariable UUID orgId, @PathVariable UUID serviceId, OrgAccess access) {
        return integrationService.status(access, serviceId);
    }

    @PutMapping
    @RequiresPermission(Permissions.PROJECT_MANAGE)
    public ConnectionStatusView configure(@PathVariable UUID orgId, @PathVariable UUID serviceId, OrgAccess access,
                                          @Valid @RequestBody ConfigureRequest body) {
        return integrationService.configure(access, serviceId, body.type());
    }

    @PostMapping("/disable")
    @RequiresPermission(Permissions.PROJECT_MANAGE)
    public ConnectionStatusView disable(@PathVariable UUID orgId, @PathVariable UUID serviceId, OrgAccess access) {
        return integrationService.disable(access, serviceId);
    }

    @PostMapping("/enable")
    @RequiresPermission(Permissions.PROJECT_MANAGE)
    public ConnectionStatusView enable(@PathVariable UUID orgId, @PathVariable UUID serviceId, OrgAccess access) {
        return integrationService.enable(access, serviceId);
    }

    @GetMapping("/instructions")
    @RequiresPermission(Permissions.PROJECT_READ)
    public InstallationGuide instructions(@PathVariable UUID orgId, @PathVariable UUID serviceId, OrgAccess access,
                                          @RequestParam IntegrationType type) {
        return guides.guide(access, serviceId, type);
    }

    public record ConfigureRequest(@NotNull IntegrationType type) {
    }
}
