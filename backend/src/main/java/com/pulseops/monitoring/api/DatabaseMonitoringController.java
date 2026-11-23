package com.pulseops.monitoring.api;

import java.time.Clock;
import java.util.UUID;

import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.tenancy.Permissions;
import com.pulseops.common.tenancy.RequiresPermission;
import com.pulseops.monitoring.service.DatabaseMonitoringService;
import com.pulseops.monitoring.service.DatabaseViews.CallList;
import com.pulseops.monitoring.service.DatabaseViews.ConnectionPool;
import com.pulseops.monitoring.service.DatabaseViews.DatabaseSummary;
import com.pulseops.monitoring.service.DatabaseViews.DbSeries;
import com.pulseops.monitoring.service.DatabaseViews.OperationList;
import com.pulseops.monitoring.service.TimeWindow;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Database monitoring for one service (read-only): summary, query shapes, trends,
 * individual slow or failed calls, and connection-pool gauges. Same time-range parameters
 * as the API monitoring endpoints; {@code slowMs} (default 500) defines a slow call.
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/services/{serviceId}/monitoring/database")
public class DatabaseMonitoringController {

    private final DatabaseMonitoringService database;
    private final Clock clock;

    public DatabaseMonitoringController(DatabaseMonitoringService database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    @GetMapping("/summary")
    @RequiresPermission(Permissions.TELEMETRY_READ)
    public DatabaseSummary summary(@PathVariable UUID orgId, @PathVariable UUID serviceId, OrgAccess access,
                                   @RequestParam(required = false) String from,
                                   @RequestParam(required = false) String to,
                                   @RequestParam(required = false) Double slowMs) {
        return database.summary(access, serviceId, TimeWindow.parse(from, to, null, clock), slowMs);
    }

    @GetMapping("/operations")
    @RequiresPermission(Permissions.TELEMETRY_READ)
    public OperationList operations(@PathVariable UUID orgId, @PathVariable UUID serviceId, OrgAccess access,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(required = false) String sort,
                                    @RequestParam(required = false) String search,
                                    @RequestParam(required = false) Integer limit,
                                    @RequestParam(required = false) Double slowMs) {
        return database.operations(access, serviceId, TimeWindow.parse(from, to, null, clock), sort, search, limit,
                slowMs);
    }

    @GetMapping("/timeseries")
    @RequiresPermission(Permissions.TELEMETRY_READ)
    public DbSeries series(@PathVariable UUID orgId, @PathVariable UUID serviceId, OrgAccess access,
                           @RequestParam(required = false) String from,
                           @RequestParam(required = false) String to,
                           @RequestParam(required = false) Integer step,
                           @RequestParam(required = false) String operation,
                           @RequestParam(required = false) Double slowMs) {
        return database.series(access, serviceId, operation, TimeWindow.parse(from, to, step, clock), slowMs);
    }

    @GetMapping("/calls")
    @RequiresPermission(Permissions.TELEMETRY_READ)
    public CallList calls(@PathVariable UUID orgId, @PathVariable UUID serviceId, OrgAccess access,
                          @RequestParam(required = false) String from,
                          @RequestParam(required = false) String to,
                          @RequestParam(required = false) Double minDurationMs,
                          @RequestParam(defaultValue = "false") boolean errorsOnly,
                          @RequestParam(required = false) Integer limit) {
        return database.calls(access, serviceId, TimeWindow.parse(from, to, null, clock), minDurationMs, errorsOnly,
                limit);
    }

    @GetMapping("/pool")
    @RequiresPermission(Permissions.TELEMETRY_READ)
    public ConnectionPool pool(@PathVariable UUID orgId, @PathVariable UUID serviceId, OrgAccess access,
                               @RequestParam(required = false) String from,
                               @RequestParam(required = false) String to,
                               @RequestParam(required = false) Integer step) {
        return database.pool(access, serviceId, TimeWindow.parse(from, to, step, clock));
    }
}
