package com.pulseops.anomaly.api;

import java.time.Clock;
import java.util.UUID;

import com.pulseops.anomaly.service.AnomalyDetectionJob;
import com.pulseops.anomaly.service.AnomalyService;
import com.pulseops.anomaly.service.AnomalyService.AnomalyPage;
import com.pulseops.anomaly.service.AnomalyService.AnomalyView;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.tenancy.Permissions;
import com.pulseops.common.tenancy.RequiresPermission;
import com.pulseops.monitoring.service.TimeWindow;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Detected anomalies: list, detail, dismiss, and the detection job's health. */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/anomalies")
public class AnomalyController {

    private final AnomalyService anomalies;
    private final AnomalyDetectionJob job;
    private final Clock clock;

    public AnomalyController(AnomalyService anomalies, AnomalyDetectionJob job, Clock clock) {
        this.anomalies = anomalies;
        this.job = job;
        this.clock = clock;
    }

    @GetMapping
    @RequiresPermission(Permissions.INCIDENT_READ)
    public AnomalyPage list(@PathVariable UUID orgId, OrgAccess access,
                            @RequestParam(required = false) String from,
                            @RequestParam(required = false) String to,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) UUID serviceId,
                            @RequestParam(required = false) String minSeverity,
                            @RequestParam(required = false) String cursor,
                            @RequestParam(required = false) Integer limit) {
        return anomalies.list(access, TimeWindow.parse(from, to, null, clock), status, serviceId, minSeverity, cursor,
                limit);
    }

    @GetMapping("/detection-status")
    @RequiresPermission(Permissions.INCIDENT_READ)
    public AnomalyDetectionJob.Status detectionStatus(@PathVariable UUID orgId, OrgAccess access) {
        return job.status();
    }

    @GetMapping("/{anomalyId}")
    @RequiresPermission(Permissions.INCIDENT_READ)
    public AnomalyView get(@PathVariable UUID orgId, @PathVariable UUID anomalyId, OrgAccess access) {
        return anomalies.get(access, anomalyId);
    }

    @PostMapping("/{anomalyId}/dismiss")
    @RequiresPermission(Permissions.INCIDENT_MANAGE)
    public AnomalyView dismiss(@PathVariable UUID orgId, @PathVariable UUID anomalyId, OrgAccess access) {
        return anomalies.dismiss(access, anomalyId);
    }
}
