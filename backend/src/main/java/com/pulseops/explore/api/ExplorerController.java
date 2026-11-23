package com.pulseops.explore.api;

import java.time.Clock;
import java.util.UUID;

import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.tenancy.Permissions;
import com.pulseops.common.tenancy.RequiresPermission;
import com.pulseops.explore.service.ExploreViews.LogHistogram;
import com.pulseops.explore.service.ExploreViews.LogPage;
import com.pulseops.explore.service.ExploreViews.TraceDetail;
import com.pulseops.explore.service.ExploreViews.TracePage;
import com.pulseops.explore.service.LogExplorerService;
import com.pulseops.explore.service.TraceExplorerService;
import com.pulseops.monitoring.service.TimeWindow;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Logs and distributed traces explorer (read-only, organization-wide or per service). */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
public class ExplorerController {

    private final LogExplorerService logs;
    private final TraceExplorerService traces;
    private final Clock clock;

    public ExplorerController(LogExplorerService logs, TraceExplorerService traces, Clock clock) {
        this.logs = logs;
        this.traces = traces;
        this.clock = clock;
    }

    @GetMapping("/logs")
    @RequiresPermission(Permissions.TELEMETRY_READ)
    public LogPage logs(@PathVariable UUID orgId, OrgAccess access,
                        @RequestParam(required = false) String from,
                        @RequestParam(required = false) String to,
                        @RequestParam(required = false) UUID serviceId,
                        @RequestParam(required = false) String minLevel,
                        @RequestParam(required = false) String levels,
                        @RequestParam(required = false) String q,
                        @RequestParam(required = false) String traceId,
                        @RequestParam(required = false) String cursor,
                        @RequestParam(required = false) Integer limit) {
        return logs.search(access, TimeWindow.parse(from, to, null, clock),
                new LogExplorerService.LogFilter(serviceId, minLevel, levels, q, traceId), cursor, limit);
    }

    @GetMapping("/logs/histogram")
    @RequiresPermission(Permissions.TELEMETRY_READ)
    public LogHistogram logHistogram(@PathVariable UUID orgId, OrgAccess access,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @RequestParam(required = false) Integer step,
                                     @RequestParam(required = false) UUID serviceId,
                                     @RequestParam(required = false) String minLevel,
                                     @RequestParam(required = false) String levels,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false) String traceId) {
        return logs.histogram(access, TimeWindow.parse(from, to, step, clock),
                new LogExplorerService.LogFilter(serviceId, minLevel, levels, q, traceId));
    }

    @GetMapping("/traces")
    @RequiresPermission(Permissions.TELEMETRY_READ)
    public TracePage traces(@PathVariable UUID orgId, OrgAccess access,
                            @RequestParam(required = false) String from,
                            @RequestParam(required = false) String to,
                            @RequestParam(required = false) UUID serviceId,
                            @RequestParam(required = false) Double minDurationMs,
                            @RequestParam(defaultValue = "false") boolean errorsOnly,
                            @RequestParam(required = false) String name,
                            @RequestParam(required = false) String sort,
                            @RequestParam(required = false) String cursor,
                            @RequestParam(required = false) Integer limit) {
        return traces.search(access, TimeWindow.parse(from, to, null, clock),
                new TraceExplorerService.TraceFilter(serviceId, minDurationMs, errorsOnly, name, sort), cursor, limit);
    }

    @GetMapping("/traces/{traceId}")
    @RequiresPermission(Permissions.TELEMETRY_READ)
    public TraceDetail trace(@PathVariable UUID orgId, @PathVariable String traceId, OrgAccess access) {
        return traces.trace(access, traceId);
    }
}
