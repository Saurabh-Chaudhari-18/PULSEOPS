package com.pulseops.credential.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.pulseops.appservice.domain.MonitoredService;
import com.pulseops.appservice.service.ServiceCatalogService;
import com.pulseops.audit.service.AuditService;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.credential.domain.IntegrationCredential;
import com.pulseops.credential.domain.IntegrationKeys;
import com.pulseops.credential.repository.IntegrationCredentialRepository;
import com.pulseops.project.domain.Project;
import com.pulseops.project.service.ProjectEvents;
import com.pulseops.project.service.ProjectService;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issue, list, revoke and rotate integration credentials. */
@Service
public class CredentialService {

    static final int MAX_EXPIRY_DAYS = 365;
    static final int MAX_GRACE_HOURS = 72;

    private final IntegrationCredentialRepository credentials;
    private final ProjectService projects;
    private final ServiceCatalogService services;
    private final AuditService audit;
    private final Clock clock;

    public CredentialService(IntegrationCredentialRepository credentials, ProjectService projects,
                             ServiceCatalogService services, AuditService audit, Clock clock) {
        this.credentials = credentials;
        this.projects = projects;
        this.services = services;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * @param serviceId    null for a project-wide key (batches must then name their service)
     * @param expiresInDays null for no expiry, otherwise 1..365
     */
    @Transactional
    public CredentialView.Issued create(OrgAccess access, UUID projectId, UUID serviceId, String name,
                                        Integer expiresInDays) {
        Project project = projects.requireLive(access.organizationId(), projectId);
        if (!project.isActive()) {
            throw ApiException.conflict("PROJECT_ARCHIVED", "Restore the project before issuing credentials");
        }
        if (serviceId != null) {
            MonitoredService service = services.requireLive(access.organizationId(), serviceId);
            if (!service.getProjectId().equals(projectId)) {
                throw ApiException.badRequest("SERVICE_NOT_IN_PROJECT", "The service does not belong to this project");
            }
        }
        Instant now = clock.instant();
        Instant expiresAt = expiryFrom(now, expiresInDays);
        String key = IntegrationKeys.generate();
        IntegrationCredential credential = credentials.save(new IntegrationCredential(access.organizationId(),
                projectId, serviceId, name.trim(), key, expiresAt, null, access.userId()));

        Map<String, Object> details = new HashMap<>();
        details.put("name", credential.getName());
        details.put("keyPrefix", credential.getKeyPrefix());
        details.put("serviceId", serviceId == null ? "project-wide" : serviceId.toString());
        audit.record(access.userId(), "credential.created", "integration_credential", credential.getId(), details);
        return CredentialView.Issued.of(CredentialView.from(credential, now), key);
    }

    @Transactional(readOnly = true)
    public List<CredentialView> listForProject(OrgAccess access, UUID projectId) {
        projects.requireLive(access.organizationId(), projectId);
        Instant now = clock.instant();
        return credentials.findByProject(access.organizationId(), projectId).stream()
                .map(c -> CredentialView.from(c, now))
                .toList();
    }

    @Transactional
    public CredentialView revoke(OrgAccess access, UUID credentialId) {
        IntegrationCredential credential = load(access, credentialId);
        Instant now = clock.instant();
        credential.revoke(now);
        audit.record(access.userId(), "credential.revoked", "integration_credential", credentialId,
                Map.of("keyPrefix", credential.getKeyPrefix()));
        return CredentialView.from(credential, now);
    }

    /**
     * Issues a replacement with the same scope and name. The old key is revoked immediately,
     * or — with a grace period — keeps working until {@code now + graceHours} so deployments
     * can switch over without dropping telemetry.
     */
    @Transactional
    public CredentialView.Issued rotate(OrgAccess access, UUID credentialId, int graceHours) {
        if (graceHours < 0 || graceHours > MAX_GRACE_HOURS) {
            throw ApiException.badRequest("INVALID_GRACE_PERIOD", "Grace period must be 0 to " + MAX_GRACE_HOURS + " hours");
        }
        IntegrationCredential old = load(access, credentialId);
        Instant now = clock.instant();
        if (!old.isUsable(now)) {
            throw ApiException.conflict("CREDENTIAL_NOT_ACTIVE", "Only active credentials can be rotated; create a new one instead");
        }
        String key = IntegrationKeys.generate();
        IntegrationCredential replacement = credentials.save(new IntegrationCredential(access.organizationId(),
                old.getProjectId(), old.getServiceId(), old.getName(), key, old.getExpiresAt(), old.getId(),
                access.userId()));
        if (graceHours == 0) {
            old.revoke(now);
        } else {
            old.expireNoLaterThan(now.plus(Duration.ofHours(graceHours)));
        }
        audit.record(access.userId(), "credential.rotated", "integration_credential", replacement.getId(),
                Map.of("replaced", old.getId().toString(), "graceHours", graceHours,
                        "keyPrefix", replacement.getKeyPrefix()));
        return CredentialView.Issued.of(CredentialView.from(replacement, now), key);
    }

    /** Deleting a project or service revokes its keys in the same transaction. */
    @EventListener
    public void onProjectDeactivated(ProjectEvents.ProjectDeactivated event) {
        if (event.deleted()) {
            int n = credentials.revokeAllForProject(event.organizationId(), event.projectId(), clock.instant());
            audit.record(event.actorId(), "credential.revoked_with_project", "project", event.projectId(),
                    Map.of("revoked", n));
        }
    }

    @EventListener
    public void onServiceDeactivated(ProjectEvents.ServiceDeactivated event) {
        if (event.deleted()) {
            int n = credentials.revokeAllForService(event.organizationId(), event.serviceId(), clock.instant());
            audit.record(event.actorId(), "credential.revoked_with_service", "service", event.serviceId(),
                    Map.of("revoked", n));
        }
    }

    private IntegrationCredential load(OrgAccess access, UUID credentialId) {
        return credentials.findInOrganization(access.organizationId(), credentialId)
                .orElseThrow(() -> ApiException.notFound("CREDENTIAL_NOT_FOUND", "Credential not found"));
    }

    static Instant expiryFrom(Instant now, Integer expiresInDays) {
        if (expiresInDays == null) {
            return null;
        }
        if (expiresInDays < 1 || expiresInDays > MAX_EXPIRY_DAYS) {
            throw ApiException.badRequest("INVALID_EXPIRY", "Expiry must be between 1 and " + MAX_EXPIRY_DAYS + " days");
        }
        return now.plus(Duration.ofDays(expiresInDays));
    }
}
