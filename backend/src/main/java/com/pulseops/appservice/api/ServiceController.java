package com.pulseops.appservice.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.pulseops.appservice.domain.Technology;
import com.pulseops.appservice.service.ServiceCatalogService;
import com.pulseops.appservice.service.ServiceView;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.tenancy.Permissions;
import com.pulseops.common.tenancy.RequiresPermission;
import com.pulseops.common.web.PageQuery;
import com.pulseops.common.web.PageResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
public class ServiceController {

    private static final String NO_CONTROL_CHARS = "^[^\\p{Cntrl}]+$";

    private final ServiceCatalogService serviceCatalog;

    public ServiceController(ServiceCatalogService serviceCatalog) {
        this.serviceCatalog = serviceCatalog;
    }

    /** All services in the organization (Services page), optionally filtered by project and name. */
    @GetMapping("/services")
    @RequiresPermission(Permissions.PROJECT_READ)
    public PageResponse<ServiceView> listAll(@PathVariable UUID orgId, OrgAccess access,
                                             @RequestParam(required = false) UUID projectId,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return serviceCatalog.list(access, projectId, q, PageQuery.of(page, size));
    }

    @GetMapping("/projects/{projectId}/services")
    @RequiresPermission(Permissions.PROJECT_READ)
    public PageResponse<ServiceView> listForProject(@PathVariable UUID orgId, @PathVariable UUID projectId,
                                                    OrgAccess access,
                                                    @RequestParam(required = false) String q,
                                                    @RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size) {
        return serviceCatalog.list(access, projectId, q, PageQuery.of(page, size));
    }

    @PostMapping("/projects/{projectId}/services")
    @RequiresPermission(Permissions.PROJECT_MANAGE)
    public ResponseEntity<ServiceView> create(@PathVariable UUID orgId, @PathVariable UUID projectId,
                                              OrgAccess access, @Valid @RequestBody CreateServiceRequest body) {
        ServiceView created = serviceCatalog.create(access, projectId, body.name(), body.description(),
                body.technology());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/services/{serviceId}")
    @RequiresPermission(Permissions.PROJECT_READ)
    public ServiceView get(@PathVariable UUID orgId, @PathVariable UUID serviceId, OrgAccess access) {
        return serviceCatalog.get(access, serviceId);
    }

    @PatchMapping("/services/{serviceId}")
    @RequiresPermission(Permissions.PROJECT_MANAGE)
    public ServiceView update(@PathVariable UUID orgId, @PathVariable UUID serviceId, OrgAccess access,
                              @Valid @RequestBody UpdateServiceRequest body) {
        return serviceCatalog.update(access, serviceId, body.name(), body.description(), body.technology());
    }

    @PostMapping("/services/{serviceId}/archive")
    @RequiresPermission(Permissions.PROJECT_MANAGE)
    public ServiceView archive(@PathVariable UUID orgId, @PathVariable UUID serviceId, OrgAccess access) {
        return serviceCatalog.archive(access, serviceId);
    }

    @PostMapping("/services/{serviceId}/restore")
    @RequiresPermission(Permissions.PROJECT_MANAGE)
    public ServiceView restore(@PathVariable UUID orgId, @PathVariable UUID serviceId, OrgAccess access) {
        return serviceCatalog.restore(access, serviceId);
    }

    @DeleteMapping("/services/{serviceId}")
    @RequiresPermission(Permissions.PROJECT_MANAGE)
    public ResponseEntity<Void> delete(@PathVariable UUID orgId, @PathVariable UUID serviceId, OrgAccess access) {
        serviceCatalog.delete(access, serviceId);
        return ResponseEntity.noContent().build();
    }

    public record CreateServiceRequest(
            @NotBlank @Size(min = 2, max = 120) @Pattern(regexp = NO_CONTROL_CHARS) String name,
            @Size(max = 2000) String description,
            Technology technology) {
    }

    public record UpdateServiceRequest(
            @Size(min = 2, max = 120) @Pattern(regexp = NO_CONTROL_CHARS) String name,
            @Size(max = 2000) String description,
            Technology technology) {
    }
}
