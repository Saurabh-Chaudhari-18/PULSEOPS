package com.pulseops.monitoring.api;

import java.time.Clock;
import java.util.UUID;

import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.tenancy.Permissions;
import com.pulseops.common.tenancy.RequiresPermission;
import com.pulseops.monitoring.service.ApiMonitoringService;
import com.pulseops.monitoring.service.MonitoringViews.EndpointList;
import com.pulseops.monitoring.service.MonitoringViews.OrganizationOverview;
import com.pulseops.monitoring.service.MonitoringViews.RuntimeMetrics;
import com.pulseops.monitoring.service.MonitoringViews.ServiceSummary;
import com.pulseops.monitoring.service.MonitoringViews.TimeSeries;
import com.pulseops.monitoring.service.RuntimeMetricsService;
import com.pulseops.monitoring.service.TimeWindow;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Application and API monitoring (read-only). Time ranges are ISO-8601 instants
 * ({@code from}, {@code to}; default the last hour) with an optional {@code step} in seconds.
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
public class MonitoringController {

    private final ApiMonitoringService api;
    private final RuntimeMetricsService runtime;
    private final Clock clock;

    public MonitoringController(ApiMonitoringService api, RuntimeMetricsService runtime, Clock clock) {
        this.api = api;
        this.runtime = runtime;
        this.clock = clock;
    }

    @GetMapping("/monitoring/overview")
    @RequiresPermission(Permissions.TELEMETRY_READ)
    public OrganizationOverview overview(@PathVariable UUID orgId, OrgAccess access,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) UUID projectId) {
        return api.overview(access, TimeWindow.parse(from, to, null, clock), projectId);
    }

    @GetMapping("/services/{serviceId}/monitoring/summary")
    @RequiresPermission(Permissions.TELEMETRY_READ)
    public ServiceSummary summary(@PathVariable UUID orgId, @PathVariable UUID serviceId, OrgAccess access,
                                  @RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to) {
        return api.summary(access, serviceId, TimeWindow.parse(from, to, null, clock));
    }

    @GetMapping("/services/{serviceId}/monitoring/timeseries")
    @RequiresPermission(Permissions.TELEMETRY_READ)
    public TimeSeries timeSeries(@PathVariable UUID orgId, @PathVariable UUID serviceId, OrgAccess access,
                                 @RequestParam(required = false) String from,
                                 @RequestParam(required = false) String to,
                                 @RequestParam(required = false) Integer step,
                                 @RequestParam(required = false) String method,
                                 @RequestParam(required = false) String route) {
        return api.timeSeries(access, serviceId, method, route, TimeWindow.parse(from, to, step, clock));
    }

    @GetMapping("/services/{serviceId}/monitoring/endpoints")
    @RequiresPermission(Permissions.TELEMETRY_READ)
    public EndpointList endpoints(@PathVariable UUID orgId, @PathVariable UUID serviceId, OrgAccess access,
                                  @RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to,
                                  @RequestParam(required = false) String sort,
                                  @RequestParam(required = false) String search,
                                  @RequestParam(required = false) Integer limit) {
        return api.endpoints(access, serviceId, TimeWindow.parse(from, to, null, clock), sort, search, limit);
    }

    @GetMapping("/services/{serviceId}/monitoring/runtime")
    @RequiresPermission(Permissions.TELEMETRY_READ)
    public RuntimeMetrics runtime(@PathVariable UUID orgId, @PathVariable UUID serviceId, OrgAccess access,
                                  @RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to,
                                  @RequestParam(required = false) Integer step) {
        return runtime.runtime(access, serviceId, TimeWindow.parse(from, to, step, clock));
    }
}
