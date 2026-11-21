package com.pulseops.audit.api;

import java.util.UUID;

import com.pulseops.audit.service.AuditService;
import com.pulseops.audit.service.AuditService.AuditEntryView;
import com.pulseops.common.tenancy.Permissions;
import com.pulseops.common.tenancy.RequiresPermission;
import com.pulseops.common.web.PageQuery;
import com.pulseops.common.web.PageResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/audit-logs")
public class AuditLogController {

    private final AuditService auditService;

    public AuditLogController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    @RequiresPermission(Permissions.AUDIT_READ)
    public PageResponse<AuditEntryView> list(@PathVariable UUID orgId,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size,
                                             @RequestParam(required = false) String action) {
        return auditService.list(PageQuery.of(page, size), action);
    }
}
