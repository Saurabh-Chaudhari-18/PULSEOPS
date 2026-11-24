package com.pulseops.incident.api;

import java.time.Clock;
import java.util.UUID;

import jakarta.validation.Valid;

import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.tenancy.Permissions;
import com.pulseops.common.tenancy.RequiresPermission;
import com.pulseops.incident.service.IncidentService;
import com.pulseops.incident.service.IncidentViews.Comment;
import com.pulseops.incident.service.IncidentViews.IncidentDetail;
import com.pulseops.incident.service.IncidentViews.IncidentPage;
import com.pulseops.monitoring.service.TimeWindow;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Incidents: automatic (from correlated anomalies) and manual, with lifecycle, timeline and comments. */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/incidents")
public class IncidentController {

    private final IncidentService incidents;
    private final Clock clock;

    public IncidentController(IncidentService incidents, Clock clock) {
        this.incidents = incidents;
        this.clock = clock;
    }

    @GetMapping
    @RequiresPermission(Permissions.INCIDENT_READ)
    public IncidentPage list(@PathVariable UUID orgId, OrgAccess access,
                             @RequestParam(required = false) String from,
                             @RequestParam(required = false) String to,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) String minSeverity,
                             @RequestParam(required = false) UUID projectId,
                             @RequestParam(required = false) UUID serviceId,
                             @RequestParam(required = false) String cursor,
                             @RequestParam(required = false) Integer limit) {
        return incidents.list(access, TimeWindow.parse(from, to, null, clock), status, minSeverity, projectId,
                serviceId, cursor, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.INCIDENT_MANAGE)
    public IncidentDetail create(@PathVariable UUID orgId, OrgAccess access,
                                 @Valid @RequestBody IncidentService.CreateRequest request) {
        return incidents.create(access, request);
    }

    @GetMapping("/{incidentId}")
    @RequiresPermission(Permissions.INCIDENT_READ)
    public IncidentDetail get(@PathVariable UUID orgId, @PathVariable UUID incidentId, OrgAccess access) {
        return incidents.get(access, incidentId);
    }

    @PatchMapping("/{incidentId}")
    @RequiresPermission(Permissions.INCIDENT_MANAGE)
    public IncidentDetail update(@PathVariable UUID orgId, @PathVariable UUID incidentId, OrgAccess access,
                                 @Valid @RequestBody IncidentService.UpdateRequest request) {
        return incidents.update(access, incidentId, request);
    }

    @PostMapping("/{incidentId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.INCIDENT_MANAGE)
    public Comment comment(@PathVariable UUID orgId, @PathVariable UUID incidentId, OrgAccess access,
                           @Valid @RequestBody IncidentService.CommentRequest request) {
        return incidents.comment(access, incidentId, request);
    }

    @DeleteMapping("/{incidentId}/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.INCIDENT_MANAGE)
    public void deleteComment(@PathVariable UUID orgId, @PathVariable UUID incidentId, @PathVariable UUID commentId,
                              OrgAccess access) {
        incidents.deleteComment(access, incidentId, commentId);
    }
}
