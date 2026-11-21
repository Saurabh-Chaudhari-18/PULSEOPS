package com.pulseops.organization.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.pulseops.audit.service.AuditService;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.web.PageQuery;
import com.pulseops.common.web.PageResponse;
import com.pulseops.organization.domain.MemberStatus;
import com.pulseops.organization.domain.OrganizationMember;
import com.pulseops.organization.repository.OrganizationMemberRepository;
import com.pulseops.organization.service.OrganizationViews.MemberView;
import com.pulseops.rbac.domain.Role;
import com.pulseops.rbac.repository.RoleRepository;
import com.pulseops.user.domain.User;
import com.pulseops.user.repository.UserRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Team management. Invariants:
 * <ul>
 *   <li>Only an OWNER can grant the OWNER role or change/remove an OWNER.</li>
 *   <li>An organization always keeps at least one active OWNER (checked under row locks).</li>
 *   <li>Removal is a soft status change; access ends on the member's next request because
 *       membership is resolved per request, not baked into tokens.</li>
 * </ul>
 * Adding members requires an existing PulseOps account; email invitations for people
 * without an account are pending (KNOWN_ISSUES).
 */
@Service
public class MemberService {

    private final OrganizationMemberRepository members;
    private final RoleRepository roles;
    private final UserRepository users;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MemberService(OrganizationMemberRepository members, RoleRepository roles, UserRepository users,
                         AuditService audit, JdbcTemplate jdbc, Clock clock) {
        this.members = members;
        this.roles = roles;
        this.users = users;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<MemberView> list(OrgAccess access, PageQuery page) {
        UUID orgId = access.organizationId();
        long total = jdbc.queryForObject(
                "SELECT count(*) FROM organization_members WHERE organization_id = ? AND status <> 'removed'",
                Long.class, orgId);
        List<MemberView> rows = jdbc.query("""
                SELECT m.id, m.user_id, u.email, u.full_name, r.id AS role_id, r.name AS role, m.status, m.joined_at
                FROM organization_members m
                JOIN users u ON u.id = m.user_id
                JOIN roles r ON r.id = m.role_id
                WHERE m.organization_id = ? AND m.status <> 'removed'
                ORDER BY lower(u.full_name), u.email
                LIMIT ? OFFSET ?
                """, (rs, i) -> new MemberView(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("email"),
                rs.getString("full_name"),
                rs.getObject("role_id", UUID.class),
                rs.getString("role"),
                MemberStatus.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
                toInstant(rs.getTimestamp("joined_at"))), orgId, page.size(), page.offset());
        return PageResponse.of(rows, page, total);
    }

    @Transactional
    public UUID add(OrgAccess access, String email, String roleName) {
        Role role = systemRole(roleName);
        requireOwnerFor(access, role.isOwnerRole());
        User user = users.findByNormalizedEmail(User.normalizeEmail(email))
                .filter(u -> u.getStatus() != com.pulseops.user.domain.UserStatus.DISABLED)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND",
                        "No active PulseOps account uses this email. Ask them to sign up first."));

        OrganizationMember member = members.findByUser(access.organizationId(), user.getId())
                .map(existing -> {
                    if (existing.isActive()) {
                        throw ApiException.conflict("ALREADY_MEMBER", "This user is already a member");
                    }
                    existing.reactivate(role.getId(), access.userId(), clock.instant());
                    return existing;
                })
                .orElseGet(() -> members.save(OrganizationMember.active(
                        access.organizationId(), user.getId(), role.getId(), access.userId(), clock.instant())));

        audit.record(access.userId(), "member.added", "organization_member", member.getId(),
                Map.of("userId", user.getId().toString(), "role", role.getName()));
        return member.getId();
    }

    @Transactional
    public void changeRole(OrgAccess access, UUID memberId, String roleName) {
        Role newRole = systemRole(roleName);
        OrganizationMember target = loadActive(access, memberId);
        Role currentRole = roles.findById(target.getRoleId()).orElseThrow();
        if (currentRole.getId().equals(newRole.getId())) {
            return;
        }
        requireOwnerFor(access, newRole.isOwnerRole() || currentRole.isOwnerRole());
        if (currentRole.isOwnerRole()) {
            ensureAnotherOwner(access.organizationId(), currentRole.getId(), target.getId());
        }
        target.changeRole(newRole.getId());
        audit.record(access.userId(), "member.role_changed", "organization_member", target.getId(),
                Map.of("from", currentRole.getName(), "to", newRole.getName()));
    }

    @Transactional
    public void remove(OrgAccess access, UUID memberId) {
        OrganizationMember target = loadActive(access, memberId);
        Role role = roles.findById(target.getRoleId()).orElseThrow();
        requireOwnerFor(access, role.isOwnerRole());
        if (role.isOwnerRole()) {
            ensureAnotherOwner(access.organizationId(), role.getId(), target.getId());
        }
        target.remove();
        audit.record(access.userId(), "member.removed", "organization_member", target.getId(),
                Map.of("userId", target.getUserId().toString()));
    }

    /** Any member may leave, except the last owner. */
    @Transactional
    public void leave(OrgAccess access) {
        OrganizationMember self = loadActive(access, access.memberId());
        if (access.isOwner()) {
            ensureAnotherOwner(access.organizationId(), access.roleId(), self.getId());
        }
        self.remove();
        audit.record(access.userId(), "member.left", "organization_member", self.getId(), Map.of());
    }

    private OrganizationMember loadActive(OrgAccess access, UUID memberId) {
        return members.findInOrganization(access.organizationId(), memberId)
                .filter(OrganizationMember::isActive)
                .orElseThrow(() -> ApiException.notFound("MEMBER_NOT_FOUND", "Member not found"));
    }

    private Role systemRole(String roleName) {
        String name = roleName == null ? "" : roleName.trim().toUpperCase(Locale.ROOT);
        return roles.findSystemRole(name)
                .orElseThrow(() -> ApiException.badRequest("UNKNOWN_ROLE", "Unknown role: " + roleName));
    }

    private static void requireOwnerFor(OrgAccess access, boolean ownerInvolved) {
        if (ownerInvolved && !access.isOwner()) {
            throw ApiException.forbidden("OWNER_REQUIRED", "Only an owner can grant, change or remove the owner role");
        }
    }

    private void ensureAnotherOwner(UUID orgId, UUID ownerRoleId, UUID excludingMemberId) {
        boolean anotherOwner = members.lockByRole(orgId, ownerRoleId, MemberStatus.ACTIVE).stream()
                .anyMatch(m -> !m.getId().equals(excludingMemberId));
        if (!anotherOwner) {
            throw ApiException.conflict("LAST_OWNER",
                    "An organization needs at least one owner. Promote another member to owner first.");
        }
    }

    private static java.time.Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
