package com.pulseops.organization.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.pulseops.audit.service.AuditService;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.tenancy.TenantContext;
import com.pulseops.organization.domain.Organization;
import com.pulseops.organization.domain.OrganizationMember;
import com.pulseops.organization.domain.OrganizationStatus;
import com.pulseops.organization.repository.OrganizationMemberRepository;
import com.pulseops.organization.repository.OrganizationRepository;
import com.pulseops.organization.service.OrganizationViews.OrganizationView;
import com.pulseops.rbac.domain.Role;
import com.pulseops.rbac.repository.RoleRepository;
import com.pulseops.user.domain.User;
import com.pulseops.user.repository.UserRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Creating, listing, updating and deleting organizations. */
@Service
public class OrganizationService {

    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final RoleRepository roles;
    private final UserRepository users;
    private final SlugGenerator slugGenerator;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OrganizationService(OrganizationRepository organizations, OrganizationMemberRepository members,
                               RoleRepository roles, UserRepository users, SlugGenerator slugGenerator,
                               AuditService audit, JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.organizations = organizations;
        this.members = members;
        this.roles = roles;
        this.users = users;
        this.slugGenerator = slugGenerator;
        this.audit = audit;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Creates an organization with the caller as OWNER. The new organization's ID is generated
     * first and bound as the tenant context BEFORE the transaction opens, so the organization,
     * the owner membership and the audit entry all pass the RLS WITH CHECK policies.
     * Deliberately not @Transactional: the context must be set before the connection is taken.
     */
    public OrganizationView create(UUID userId, String name, String description) {
        User creator = users.findById(userId).filter(User::isActive)
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Authentication required"));
        UUID orgId = UUID.randomUUID();
        return TenantContext.callInOrganization(orgId, () -> tx.execute(status -> {
            Role owner = roles.findSystemRole(Role.OWNER)
                    .orElseThrow(() -> new IllegalStateException("System role OWNER missing (V7 seed)"));
            Organization org = new Organization(orgId, name.trim(), slugGenerator.generate(name),
                    blankToNull(description), creator.getId());
            organizations.save(org);
            members.save(OrganizationMember.active(orgId, creator.getId(), owner.getId(), null, clock.instant()));
            audit.record(creator.getId(), "organization.created", "organization", orgId,
                    Map.of("name", org.getName(), "slug", org.getSlug()));
            organizations.flush();
            return OrganizationView.from(organizations.findById(orgId).orElseThrow(), owner.getName());
        }));
    }

    /** Organizations the user actively belongs to (visible via the V9 user-scoped policies). */
    public List<OrganizationView> listForUser(UUID userId) {
        return jdbc.query("""
                SELECT o.id, o.name, o.slug, o.description, o.status, o.created_at, r.name AS role
                FROM organization_members m
                JOIN organizations o ON o.id = m.organization_id
                JOIN roles r ON r.id = m.role_id
                WHERE m.user_id = ? AND m.status = 'active' AND o.status = 'active'
                ORDER BY lower(o.name)
                """, (rs, i) -> new OrganizationView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("slug"),
                rs.getString("description"),
                OrganizationStatus.valueOf(rs.getString("status").toUpperCase(java.util.Locale.ROOT)),
                rs.getString("role"),
                toInstant(rs.getTimestamp("created_at"))), userId);
    }

    @Transactional(readOnly = true)
    public OrganizationView get(OrgAccess access) {
        return OrganizationView.from(load(access.organizationId()), access.roleName());
    }

    @Transactional
    public OrganizationView update(OrgAccess access, String name, String description) {
        Organization org = load(access.organizationId());
        org.update(name == null ? null : name.trim(), description);
        audit.record(access.userId(), "organization.updated", "organization", org.getId(),
                Map.of("name", org.getName()));
        return OrganizationView.from(org, access.roleName());
    }

    /** Soft delete; requires typing the slug to confirm. Members lose access immediately. */
    @Transactional
    public void delete(OrgAccess access, String confirmSlug) {
        Organization org = load(access.organizationId());
        if (!org.getSlug().equals(confirmSlug)) {
            throw ApiException.badRequest("CONFIRMATION_MISMATCH", "Type the organization slug to confirm deletion");
        }
        org.markDeleted();
        audit.record(access.userId(), "organization.deleted", "organization", org.getId(),
                Map.of("slug", org.getSlug()));
    }

    private Organization load(UUID orgId) {
        return organizations.findById(orgId)
                .filter(o -> o.getStatus() == OrganizationStatus.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("ORGANIZATION_NOT_FOUND", "Organization not found"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static java.time.Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
