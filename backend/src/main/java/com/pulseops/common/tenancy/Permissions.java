package com.pulseops.common.tenancy;

/** Permission codes; must match the seed in V7__seed_rbac.sql. */
public final class Permissions {

    public static final String ORG_MANAGE = "org.manage";
    public static final String ORG_MEMBERS_MANAGE = "org.members.manage";
    public static final String PROJECT_READ = "project.read";
    public static final String PROJECT_MANAGE = "project.manage";
    public static final String CREDENTIAL_MANAGE = "credential.manage";
    public static final String TELEMETRY_READ = "telemetry.read";
    public static final String INCIDENT_READ = "incident.read";
    public static final String INCIDENT_MANAGE = "incident.manage";
    public static final String ALERT_MANAGE = "alert.manage";
    public static final String ANALYTICS_READ = "analytics.read";
    public static final String AUDIT_READ = "audit.read";

    private Permissions() {
    }
}
