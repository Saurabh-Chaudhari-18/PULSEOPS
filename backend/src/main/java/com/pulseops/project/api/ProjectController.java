package com.pulseops.project.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.tenancy.Permissions;
import com.pulseops.common.tenancy.RequiresPermission;
import com.pulseops.common.web.PageQuery;
import com.pulseops.common.web.PageResponse;
import com.pulseops.project.domain.Environment;
import com.pulseops.project.domain.ProjectDetails;
import com.pulseops.project.domain.ProjectStatus;
import com.pulseops.project.service.ProjectService;
import com.pulseops.project.service.ProjectView;

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
@RequestMapping("/api/v1/organizations/{orgId}/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    @RequiresPermission(Permissions.PROJECT_READ)
    public PageResponse<ProjectView> list(@PathVariable UUID orgId, OrgAccess access,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(required = false) ProjectStatus status,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        return projectService.list(access, q, status, PageQuery.of(page, size));
    }

    @PostMapping
    @RequiresPermission(Permissions.PROJECT_MANAGE)
    public ResponseEntity<ProjectView> create(@PathVariable UUID orgId, OrgAccess access,
                                              @Valid @RequestBody CreateProjectRequest body) {
        ProjectView created = projectService.create(access, body.toDetails());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{projectId}")
    @RequiresPermission(Permissions.PROJECT_READ)
    public ProjectView get(@PathVariable UUID orgId, @PathVariable UUID projectId, OrgAccess access) {
        return projectService.get(access, projectId);
    }

    @PatchMapping("/{projectId}")
    @RequiresPermission(Permissions.PROJECT_MANAGE)
    public ProjectView update(@PathVariable UUID orgId, @PathVariable UUID projectId, OrgAccess access,
                              @Valid @RequestBody UpdateProjectRequest body) {
        return projectService.update(access, projectId, body.toDetails());
    }

    @PostMapping("/{projectId}/archive")
    @RequiresPermission(Permissions.PROJECT_MANAGE)
    public ProjectView archive(@PathVariable UUID orgId, @PathVariable UUID projectId, OrgAccess access) {
        return projectService.archive(access, projectId);
    }

    @PostMapping("/{projectId}/restore")
    @RequiresPermission(Permissions.PROJECT_MANAGE)
    public ProjectView restore(@PathVariable UUID orgId, @PathVariable UUID projectId, OrgAccess access) {
        return projectService.restore(access, projectId);
    }

    @DeleteMapping("/{projectId}")
    @RequiresPermission(Permissions.PROJECT_MANAGE)
    public ResponseEntity<Void> delete(@PathVariable UUID orgId, @PathVariable UUID projectId, OrgAccess access,
                                       @Valid @RequestBody DeleteProjectRequest body) {
        projectService.delete(access, projectId, body.confirmName());
        return ResponseEntity.noContent().build();
    }

    private static final String NO_CONTROL_CHARS = "^[^\\p{Cntrl}]+$";

    public record CreateProjectRequest(
            @NotBlank @Size(min = 2, max = 120) @Pattern(regexp = NO_CONTROL_CHARS) String name,
            @Size(max = 2000) String description,
            @Size(max = 200) String techStack,
            Environment environment,
            @Size(max = 64) String region,
            @Size(max = 64) String applicationType) {

        ProjectDetails toDetails() {
            return new ProjectDetails(name, description, techStack, environment, region, applicationType);
        }
    }

    /** Null fields stay unchanged; blank optional strings clear the field. */
    public record UpdateProjectRequest(
            @Size(min = 2, max = 120) @Pattern(regexp = NO_CONTROL_CHARS) String name,
            @Size(max = 2000) String description,
            @Size(max = 200) String techStack,
            Environment environment,
            @Size(max = 64) String region,
            @Size(max = 64) String applicationType) {

        ProjectDetails toDetails() {
            return new ProjectDetails(name, description, techStack, environment, region, applicationType);
        }
    }

    public record DeleteProjectRequest(@NotBlank @Size(max = 120) String confirmName) {
    }
}
